package src.backend.bus.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
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

import src.backend.bus.dto.BusRegisterRequest;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;

/**
 * 호차 유일성의 <b>동시성</b> — 같은 학원에 같은 호차 등록 요청 2건이 겹쳐 들어오는 상황
 * (Ruling 164 · 게이트 리뷰 Important 4).
 *
 * <p>애플리케이션 선검사만으로는 이 형태를 막지 못한다. 두 트랜잭션은 서로의 미커밋 INSERT 를 보지
 * 못해 <b>둘 다</b> "그 호차 없음" 을 읽고 지나가며, 그 뒤 {@code uk_bus_academy_bus_no} 가 하나를
 * 거부한다. 그 거부가 {@code 409} 로 옮겨지지 않으면 사용자에게 {@code 500} 이 나간다 — 리뷰어가
 * 프로브로 그 형태를 실측했다.
 *
 * <p><b>어느 층이 잡는지를 타이밍에 맡기지 않는다.</b> 먼저 들어간 트랜잭션은 상대가 실제로
 * {@code INSERT} 에서 <b>DB 잠금을 기다리는 것을 확인한 뒤에야</b> 커밋한다
 * ({@link #상대가_INSERT_에서_대기할_때까지_커밋을_미룬다()}). 신호만으로 순서를 맞추면 상대의
 * 선검사가 <b>먼저 들어간 쪽의 커밋 이후</b>에 도는 경우가 생겨, 그때는 선검사가 잡아 버려
 * <b>제약 위반 번역 경로가 한 번도 실행되지 않는다</b> — 그 상태에서는 번역을 통째로 지워도 이
 * 테스트가 초록이다(수정 라운드 1 의 음성 대조 N5b 로 실측).
 *
 * <p>이 클래스에 {@code @Transactional} 이 부재한 것이 요점이다 — 테스트가 트랜잭션을 하나 열고
 * 있으면 두 스레드가 그것을 공유해 "서로의 커밋을 보지 못하는" 상황 자체가 만들어지지 않는다. 대신
 * 만든 행을 {@link #뒷정리한다()} 가 직접 지운다({@code AcademyStaffQuotaConcurrencyTest} 와 같은 형태).
 */
@SpringBootTest
class BusRegistrationConcurrencyTest {

    /** 시드와 겹치지 않는 호차 — 뒷정리가 이 값으로 자기 행만 지운다. */
    private static final String BUS_NO = "P5T2CONC호차";

    /** 시드의 학원 A 와 그 관계자 계정. */
    private static final long ACADEMY_A_ID = 1L;

    private static final long STAFF_A_ACCOUNT_ID = 2L;

    /**
     * 스레드 하나가 상대를 기다리는 상한 — <b>정상 흐름에서는 소진되지 않는다.</b> 여기 걸리면
     * 상대가 죽었거나 DB 잠금이 안 풀린 것이라, 테스트가 매달리는 대신 실패로 드러나야 한다.
     *
     * <p>20 → 45 로 올림(Phase 10 T1). ⚠ <b>그 인상의 근거였던 진단은 F5 S3 실측으로 반증됐다.</b>
     * "캐시된 {@code @SpringBootTest} 컨텍스트 다수가 만드는 자원 경합이 20초 예산을 넘긴다" 였는데,
     * 45초로 올린 뒤에도 전체 실행에서 이 시험이 예산을 다 쓰고 그대로 실패했다. 그 순간 상대는
     * 44초 내내 {@code Lock/transactionid} 로 대기 중이었다 — 즉 느려서가 아니라 <b>폴링이 상대를
     * 보지 못했다.</b> 원인은 폴링이 읽는 {@code pg_stat_activity} 가 트랜잭션 단위로 캐시되는 것이고
     * (PostgreSQL {@code stats_fetch_consistency} 기본값 {@code cache}), 회차마다
     * {@code pg_stat_clear_snapshot()} 으로 스냅숏을 버리는 것으로 고쳤다. 이 값은 이제 상대가
     * 죽었을 때의 상한으로만 남는다.
     */
    private static final long TIMEOUT_SECONDS = 45;

    /** 상대가 잠금을 기다리는지 물어보는 간격 — 고정 대기가 아니라 상태를 물어 기다리기 위한 값이다. */
    private static final long POLL_INTERVAL_MILLIS = 50;

    @Autowired
    private BusCommandService busCommandService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void 앞선_실행이_남긴_행을_지운다() {
        뒷정리한다();
    }

    /** 이 클래스는 실제 커밋을 남기므로 지우는 것도 직접 한다 — 자기 호차 행만 지운다. */
    @AfterEach
    void 뒷정리한다() {
        jdbcTemplate.update("DELETE FROM bus WHERE bus_no = ?", BUS_NO);
    }

    /**
     * 동시 2요청은 성공 1건 · 실패 1건이고, 실패는 {@code 409 DUPLICATE_BUS_NO} 다.
     *
     * <p>실패한 쪽이 선검사에서 걸렸는지 DB 에서 걸렸는지는 타이밍이 정하며 여기서 가리지 않는다 —
     * <b>어느 쪽이든 같은 코드로 나가야 한다</b> 는 것이 이 단언의 내용이다. 세 단언이 각각 다른
     * 사고를 막는다: 성공 1건(둘 다 통과하면 중복 행) · 실패가 {@code DUPLICATE_BUS_NO}(500 누출) ·
     * DB 행 1개(응답과 저장 상태가 갈리는 것).
     */
    @Test
    void 같은_학원에_같은_호차를_동시에_등록하면_성공_1건_실패_1건이고_500_이_부재한다() throws Exception {
        CountDownLatch 먼저_들어갔다 = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Optional<ErrorCode>> results;
        try {
            Future<Optional<ErrorCode>> 먼저 = pool.submit(() -> 등록한다("10가0001", 먼저_들어갔다, true));
            Future<Optional<ErrorCode>> 나중 = pool.submit(() -> {
                먼저_들어갔다.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return 등록한다("10가0002", null, false);
            });

            results = List.of(먼저.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    나중.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            // 뒷정리가 잠금을 기다리지 않도록 두 트랜잭션이 끝난 것을 먼저 확인한다.
            pool.shutdownNow();
            pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        assertThat(results.stream().filter(Optional::isEmpty).count())
                .as("성공은 정확히 1건 — 2건이면 같은 호차가 두 번 등록된 것이고 0건이면 정상 등록까지 막힌 것이다")
                .isEqualTo(1);
        assertThat(results.stream().flatMap(Optional::stream).toList())
                .as("실패는 500(INTERNAL_ERROR)이 아니라 409 DUPLICATE_BUS_NO 여야 한다 — "
                        + "여기까지 온 실패는 선검사를 지나 DB 가 거부한 것이라 제약 위반 번역이 유일한 방어다")
                .containsExactly(ErrorCode.DUPLICATE_BUS_NO);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bus WHERE academy_id = ? AND bus_no = ?", Integer.class,
                ACADEMY_A_ID, BUS_NO))
                .as("응답과 무관하게 DB 에 남은 행도 1개여야 한다")
                .isEqualTo(1);
    }

    /**
     * 한 트랜잭션 안에서 차량을 등록한다 — {@code POST /staff/buses} 가 할 일과 같다.
     *
     * <p>두 신호로 겹침을 만든다. 고정 대기({@code Thread.sleep})를 쓰지 않는 이유는 그 값이 기계
     * 속도에 묶여, 느린 기계에서는 겹침이 사라지고 빠른 기계에서는 그만큼 매번 낭비되기 때문이다.
     *
     * <p>예외를 {@link BusinessException} 으로만 받는다 — 그 밖의 예외(예: 번역되지 않은
     * {@code DataIntegrityViolationException})는 여기서 삼키지 않고 그대로 올라가 테스트를 실패시킨다.
     * 삼키면 {@code 500} 누출이 "실패 1건" 으로 세어져 이 테스트가 사고를 통과시킨다.
     *
     * @param 들어갔음     INSERT 를 보낸 직후 내리는 신호 — 상대가 이때부터 착수한다
     * @param 상대를_기다림 상대가 INSERT 에서 잠금을 기다리는 것을 확인하고서야 커밋할지
     * @return 성공이면 빈 값, 호차 충돌이면 그 에러 코드
     */
    private Optional<ErrorCode> 등록한다(String plateNo, CountDownLatch 들어갔음, boolean 상대를_기다림) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try {
            return transaction.execute(status -> {
                busCommandService.register(관계자A(), new BusRegisterRequest(BUS_NO, plateNo, 16, null));
                if (들어갔음 != null) {
                    들어갔음.countDown();
                }
                if (상대를_기다림) {
                    상대가_INSERT_에서_대기할_때까지_커밋을_미룬다();
                }
                return Optional.<ErrorCode>empty();
            });
        } catch (BusinessException e) {
            return Optional.of(e.getErrorCode());
        }
    }

    /**
     * 상대 세션이 {@code bus} INSERT 에서 잠금을 기다리는 상태가 될 때까지 커밋을 미룬다.
     *
     * <p><b>이 대기가 이 테스트의 결정성을 만든다.</b> 상대가 그 상태에 있다는 것은 상대의 선검사가
     * 이미 끝났고(내 행이 미커밋이라 보이지 않았고) 이제 DB 만 남았다는 뜻이다 — 그래서 내가 커밋하는
     * 순간 상대는 <b>반드시</b> 제약 위반을 받는다. 신호(latch)만으로 순서를 맞추면 상대의 선검사가
     * 내 커밋 뒤에 돌 수 있고, 그러면 선검사가 잡아 번역 경로가 실행되지 않는다.
     *
     * <p>고정 대기({@code Thread.sleep})가 아니라 <b>상태를 물어</b> 기다린다 — 고정 값은 기계 속도에
     * 묶여 느린 기계에서는 순서가 어긋나고 빠른 기계에서는 그만큼 매번 낭비된다. 상한에 걸리면
     * 그대로 커밋하고, 그 경우 뒤의 단언이 실패로 드러낸다.
     */
    private void 상대가_INSERT_에서_대기할_때까지_커밋을_미룬다() {
        long 마감 = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < 마감) {
            // pg_stat_activity 는 트랜잭션 단위로 캐시된다(PostgreSQL 16 · stats_fetch_consistency
            // 기본값 cache) — 이 트랜잭션이 처음 읽은 스냅숏이 끝까지 재사용되므로, 상대가 대기에
            // 들어가기 전에 첫 조회가 나가면 그 뒤로는 몇 번을 물어도 0 이 돌아온다. 그러면 이 루프가
            // 상한을 다 쓰고 바깥 Future.get 이 그보다 먼저 만료해 TimeoutException 만 남는다
            // (F5 S3 실측 — 상대는 44초 내내 Lock/transactionid 로 대기 중이었는데 0 이 보였다)
            jdbcTemplate.execute("SELECT pg_stat_clear_snapshot()");
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

    private AuthUser 관계자A() {
        return new AuthUser(STAFF_A_ACCOUNT_ID, ACADEMY_A_ID, Role.STAFF, AccountStatus.ACTIVE);
    }
}
