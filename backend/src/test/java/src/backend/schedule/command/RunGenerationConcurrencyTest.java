package src.backend.schedule.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import src.backend.run.command.RunCommandService;
import src.backend.run.entity.RunDraft;
import src.backend.schedule.entity.Schedule;
import src.backend.schedule.repository.ScheduleRepository;

/**
 * 일일 회차 생성의 <b>동시성</b>(목표 7) — 배치가 겹쳐 도는 상황이다. 재기동 직후·수동 재실행이
 * 실제로 이 형태를 만든다.
 *
 * <p><b>애플리케이션 선검사만으로는 막지 못한다.</b> 두 트랜잭션은 서로의 미커밋 INSERT 를 보지 못해
 * <b>둘 다</b> "그 회차 없음" 을 읽고 지나가며, 그 뒤 {@code uk_run_bus_date_direction_depart} 가
 * 하나를 거부한다. 그 거부를 {@code DUPLICATE_RUN} 으로 옮겨 배치가 삼키지 않으면
 * {@code DataIntegrityViolationException} 이 그대로 올라가 <b>그 실행이 통째로 죽는다.</b>
 *
 * <p><b>어느 층이 잡는지를 타이밍에 맡기지 않는다.</b> 먼저 들어간 트랜잭션은 배치가 실제로
 * {@code INSERT} 에서 <b>DB 잠금을 기다리는 것을 확인한 뒤에야</b> 커밋한다
 * ({@link #상대가_INSERT_에서_대기할_때까지_커밋을_미룬다()}). 신호만으로 순서를 맞추면 배치의
 * 선검사가 커밋 이후에 돌 수 있고, 그때는 선검사가 잡아 <b>제약 위반 번역 경로가 한 번도 실행되지
 * 않는다</b> — 그 상태에서는 번역을 통째로 지워도 이 테스트가 초록이다
 * ({@code BusRegistrationConcurrencyTest} 가 같은 함정을 이미 실측했다).
 *
 * <p>{@code @Transactional} 이 부재한 것이 요점이다 — 테스트가 트랜잭션을 하나 열고 있으면 두 스레드가
 * 그것을 공유해 "서로의 커밋을 보지 못하는" 상황 자체가 만들어지지 않는다.
 */
@SpringBootTest
class RunGenerationConcurrencyTest {

    /** 시드 학원 A 와 그 1호차. */
    private static final long ACADEMY_A_ID = 1L;

    private static final long BUS_A_ID = 1L;

    /** 날짜 후보의 시작점 — 시드 스케줄이 쓰지 않는 요일을 찾을 때까지 하루씩 민다. */
    private static final LocalDate FIRST_CANDIDATE_DATE = LocalDate.of(2030, 4, 1);

    /** 이 테스트가 만드는 행만 가려내는 표시. */
    private static final String MARKER = "P5T5동시배치";

    private static final String DEPART_TIME = "14:25";

    /**
     * 스레드 하나가 상대를 기다리는 상한 — <b>정상 흐름에서는 소진되지 않는다.</b> 여기 걸리면 상대가
     * 죽었거나 DB 잠금이 안 풀린 것이라, 테스트가 매달리는 대신 실패로 드러나야 한다.
     *
     * <p>20 → 45 로 올림(Phase 10 T1). 순서 강제 자체(잠금 대기 폴링)는 이미 결정적이라 손대지
     * 않았다 — 전체 테스트 묶음 아래에서 난 {@code TimeoutException} 은 순서가 흔들려서가 아니라,
     * 캐시된 {@code @SpringBootTest} 컨텍스트 다수가 만드는 자원 경합 아래 같은 왕복이 20초 예산을
     * 넘겨서다(Phase 9 이월 ①의 "순서를 정할 수단이 부재" 진단은 이 폴링이 이미 붙은 뒤에 쓰여
     * 낡았다). 예산만 넉넉히 늘린다.
     */
    private static final long TIMEOUT_SECONDS = 45;

    /** 상대가 잠금을 기다리는지 물어보는 간격 — 고정 대기가 아니라 상태를 물어 기다리기 위한 값이다. */
    private static final long POLL_INTERVAL_MILLIS = 50;

    @Autowired
    private RunGenerationService runGenerationService;

    @Autowired
    private RunCommandService runCommandService;

    @Autowired
    private ScheduleRepository scheduleRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private LocalDate serviceDate;

    private long scheduleId;

    @BeforeEach
    void 판정_날짜와_스케줄을_준비한다() {
        뒷정리한다();
        Set<String> 이미_쓰는_요일 = new HashSet<>(
                jdbcTemplate.queryForList("SELECT DISTINCT weekday FROM schedule", String.class));
        LocalDate 후보 = FIRST_CANDIDATE_DATE;
        while (이미_쓰는_요일.contains(요일명(후보))) {
            후보 = 후보.plusDays(1);
        }
        serviceDate = 후보;
        scheduleId = jdbcTemplate.queryForObject("""
                INSERT INTO schedule (academy_id, bus_id, weekday, direction, depart_time, origin_name,
                                      destination_name, active)
                VALUES (?, ?, ?, 'to_academy', CAST(? AS time), ?, '바래다학원 A', true)
                RETURNING id""",
                Long.class, ACADEMY_A_ID, BUS_A_ID, 요일명(serviceDate), DEPART_TIME, MARKER);
    }

    /** 이 클래스는 실제 커밋을 남기므로 지우는 것도 직접 한다 — 자기 표시가 붙은 행만 지운다. */
    @AfterEach
    void 뒷정리한다() {
        jdbcTemplate.update("DELETE FROM run WHERE origin_name = ?", MARKER);
        jdbcTemplate.update("DELETE FROM schedule WHERE origin_name = ?", MARKER);
    }

    /**
     * 회차 생성이 겹쳐도 행이 늘지 않고 배치가 죽지 않는다.
     *
     * <p>세 단언이 각각 다른 사고를 막는다 — 배치가 예외를 던지지 않음({@code 500} 누출·실행 중단) ·
     * 그 실행의 생성 건수 0(이미 있는 것을 만들었다고 보고하는 것) · DB 행 1개(중복 회차).
     */
    @Test
    void 회차_생성_배치를_동시에_두_번_돌려도_회차가_늘지_않고_500_이_부재한다() throws Exception {
        CountDownLatch 먼저_들어갔다 = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int 배치가_만든_수;
        try {
            Future<Void> 먼저 = pool.submit(() -> {
                먼저_INSERT_하고_상대가_막힐_때까지_커밋을_미룬다(먼저_들어갔다);
                return null;
            });
            // 배치는 상대의 INSERT 가 실제로 들어간 뒤에 출발한다. 상한에 걸려 그냥 출발하면 배치가
            // 혼자 회차를 만들고, 그것은 아래 생성 건수 단언이 1 로 실패시킨다.
            Future<Integer> 배치 = pool.submit(() -> {
                먼저_들어갔다.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return runGenerationService.generate(serviceDate);
            });

            배치가_만든_수 = 배치.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            먼저.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            // 뒷정리가 잠금을 기다리지 않도록 두 트랜잭션이 끝난 것을 먼저 확인한다.
            pool.shutdownNow();
            pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        assertThat(배치가_만든_수)
                .as("상대가 먼저 만든 회차는 건너뛴 것이라 0 건이어야 한다 — 1 이면 만들지 않은 것을 만들었다고 센 것이다")
                .isZero();
        assertThat(회차_수())
                .as("행이 2개면 같은 차량이 같은 시각에 두 번 출발하는 회차가 생긴 것이다")
                .isEqualTo(1);
    }

    /**
     * 배치가 <b>겹치지 않을 때는</b> 정상적으로 만든다 — 위 단언이 "아무것도 안 만드는 구현" 과
     * 구별되게 하는 반대편이다.
     */
    @Test
    void 겹치지_않으면_배치가_회차를_만든다() {
        assertThatCode(() -> assertThat(runGenerationService.generate(serviceDate)).isEqualTo(1))
                .doesNotThrowAnyException();
        assertThat(회차_수()).isEqualTo(1);
    }

    /**
     * 트랜잭션 하나를 열어 회차를 INSERT 하고, 배치가 그 행에 막힐 때까지 커밋을 미룬다.
     *
     * <p>이 대기가 이 테스트의 결정성을 만든다 — 상대가 잠금을 기다린다는 것은 상대의 선검사가 이미
     * 끝났고(내 행이 미커밋이라 보이지 않았고) 이제 DB 만 남았다는 뜻이라, 내가 커밋하는 순간 상대는
     * <b>반드시</b> 제약 위반을 받는다.
     *
     * <p><b>배치를 푸는 신호를 이 메서드가 INSERT 직후에 보낸다</b>({@code 먼저_들어갔다}). 신호를
     * 호출부에서 보내면 두 스레드는 순서 없이 출발하고, 배치가 먼저 커밋해 버리면 이쪽의
     * {@code create} 가 <b>선검사</b>에서 {@code DUPLICATE_RUN} 을 던져 그대로 밖으로 나간다 — 배치
     * 경로만 그 예외를 삼키기 때문이다. 실제로 그 형태로 6회 중 1회 실패했고, INSERT 앞에 300ms 를
     * 넣어 순서를 뒤집으면 3회 중 3회 같은 자리에서 실패한다.
     */
    private void 먼저_INSERT_하고_상대가_막힐_때까지_커밋을_미룬다(CountDownLatch 먼저_들어갔다) {
        Schedule schedule = scheduleRepository.findById(scheduleId).orElseThrow();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            runCommandService.create(new RunDraft(schedule.getAcademyId(), schedule.getBusId(), schedule.getId(),
                    serviceDate, schedule.getDirection(), schedule.getDepartTime(), schedule.getOriginName(),
                    schedule.getDestinationName(), schedule.getEstDurationMin()));
            먼저_들어갔다.countDown();
            상대가_INSERT_에서_대기할_때까지_커밋을_미룬다();
        });
    }

    /**
     * 다른 세션이 {@code run} INSERT 에서 잠금을 기다리는 상태가 될 때까지 커밋을 미룬다.
     *
     * <p>고정 대기({@code Thread.sleep})가 아니라 <b>상태를 물어</b> 기다린다 — 고정 값은 기계 속도에
     * 묶여 느린 기계에서는 순서가 어긋나고 빠른 기계에서는 그만큼 매번 낭비된다. 상한에 걸리면 그대로
     * 커밋하고, 그 경우 뒤의 단언이 실패로 드러낸다.
     */
    private void 상대가_INSERT_에서_대기할_때까지_커밋을_미룬다() {
        long 마감 = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < 마감) {
            Integer 대기중 = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                            + "AND wait_event_type = 'Lock' AND wait_event = 'transactionid'",
                    Integer.class);
            if (대기중 != null && 대기중 > 0) {
                return;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private int 회차_수() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM run WHERE origin_name = ?", Integer.class,
                MARKER);
    }

    /** {@code schedule.weekday} 의 값 공간({@code mon}~{@code sun}) 표기. */
    private static String 요일명(LocalDate date) {
        return date.getDayOfWeek().name().substring(0, 3).toLowerCase(Locale.ROOT);
    }
}
