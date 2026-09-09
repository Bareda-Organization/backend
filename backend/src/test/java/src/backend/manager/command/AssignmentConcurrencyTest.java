package src.backend.manager.command;

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

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.manager.dto.AssignmentRequest;
import src.backend.run.command.RunCommandService;
import src.backend.run.dto.RunCreateRequest;

/**
 * 한 회차의 <b>같은 자리</b>를 두 요청이 동시에 채우려는 상황(MGR-05 · §5.14 ·
 * {@code uk_assignment_run_role}).
 *
 * <p>순차 요청은 <b>교체</b>로 처리되므로(§5.14) 이 제약이 발동하는 것은 경합뿐이다 — 두 트랜잭션은
 * 서로의 미커밋 INSERT 를 보지 못해 둘 다 "빈 자리" 를 읽고 각자 INSERT 를 보낸다. 그 거부를
 * {@code 409 DUPLICATE_ASSIGNMENT} 로 옮기지 않으면 {@code 500} 이 나가 "서버가 고장났다" 와
 * "누가 먼저 채웠다" 가 구별되지 않는다.
 *
 * <p><b>이것이 "회차당 기사 1명·동승자 1명" 을 실제로 강제하는 자리다</b> — 경고 축(MGR-06)과 달리
 * 여기는 차단이며, 두 축을 섞으면 경고가 차단으로 굳는다(Ruling 152).
 *
 * <p>먼저 들어간 트랜잭션은 상대가 실제로 DB 잠금을 기다리는 것을 확인한 뒤에야 커밋한다 — 신호만으로
 * 순서를 맞추면 상대의 조회가 커밋 이후에 돌아 <b>교체 경로</b>로 빠지고, 그때는 제약 위반 번역이 한
 * 번도 실행되지 않는다({@code BusRegistrationConcurrencyTest} 가 같은 함정을 실측했다).
 */
@SpringBootTest
class AssignmentConcurrencyTest {

    private static final long STAFF_A_ACCOUNT_ID = 2L;

    private static final long ACADEMY_A_ID = 1L;

    private static final long BUS_A1_ID = 1L;

    /** 시드 학원 A 의 기사 둘 — 같은 자리를 서로 다른 사람으로 채우려 한다. */
    private static final long 강기사 = 1L;

    private static final long 오기사 = 2L;

    /** 시드 회차와 겹치지 않는 날짜·시각. */
    private static final String SERVICE_DATE = "2031-07-09";

    private static final String DEPART_TIME = "08:40";

    private static final String MARKER = "P5T5동시배치자리";

    private static final long TIMEOUT_SECONDS = 20;

    private static final long POLL_INTERVAL_MILLIS = 50;

    @Autowired
    private AssignmentCommandService assignmentCommandService;

    @Autowired
    private RunCommandService runCommandService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long runId;

    @BeforeEach
    void 대상_회차를_만든다() {
        뒷정리한다();
        runId = runCommandService.add(관계자A(), new RunCreateRequest(BUS_A1_ID, SERVICE_DATE, "to_academy",
                DEPART_TIME, MARKER, "바래다학원 A", null)).id();
    }

    /** 이 클래스는 실제 커밋을 남기므로 지우는 것도 직접 한다 — 자기 표시가 붙은 회차와 그 배치만 지운다. */
    @AfterEach
    void 뒷정리한다() {
        jdbcTemplate.update("DELETE FROM assignment WHERE run_id IN "
                + "(SELECT id FROM run WHERE origin_name = ?)", MARKER);
        jdbcTemplate.update("DELETE FROM run WHERE origin_name = ?", MARKER);
    }

    /**
     * 동시 2요청은 성공 1건 · 실패 1건이고, 실패는 {@code 409 DUPLICATE_ASSIGNMENT} 다.
     *
     * <p>세 단언이 각각 다른 사고를 막는다 — 성공 1건(둘 다 통과하면 한 회차에 기사 2명) · 실패가
     * {@code DUPLICATE_ASSIGNMENT}(500 누출) · DB 행 1개(응답과 저장 상태가 갈리는 것).
     */
    @Test
    void 같은_역할을_동시에_채우면_성공_1건_실패_1건이고_500_이_부재한다() throws Exception {
        CountDownLatch 먼저_들어갔다 = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Optional<ErrorCode>> results;
        try {
            Future<Optional<ErrorCode>> 먼저 = pool.submit(() -> 배치한다(강기사, 먼저_들어갔다, true));
            Future<Optional<ErrorCode>> 나중 = pool.submit(() -> {
                먼저_들어갔다.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return 배치한다(오기사, null, false);
            });

            results = List.of(먼저.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    나중.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            // 뒷정리가 잠금을 기다리지 않도록 두 트랜잭션이 끝난 것을 먼저 확인한다.
            pool.shutdownNow();
            pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        assertThat(results.stream().filter(Optional::isEmpty).count())
                .as("성공은 정확히 1건 — 2건이면 한 회차에 기사가 두 명이고 0건이면 정상 배치까지 막힌 것이다")
                .isEqualTo(1);
        assertThat(results.stream().flatMap(Optional::stream).toList())
                .as("실패는 500 이 아니라 409 DUPLICATE_ASSIGNMENT 여야 한다 — 여기까지 온 실패는 조회를 지나 "
                        + "DB 가 거부한 것이라 제약 위반 번역이 유일한 방어다")
                .containsExactly(ErrorCode.DUPLICATE_ASSIGNMENT);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM assignment WHERE run_id = ? AND role = 'driver'", Integer.class, runId))
                .as("응답과 무관하게 DB 에 남은 행도 1개여야 한다")
                .isEqualTo(1);
    }

    /**
     * 한 트랜잭션 안에서 기사 자리를 채운다 — {@code PATCH /staff/runs/{runId}/assignment} 가 할 일과 같다.
     *
     * <p>예외를 {@link BusinessException} 으로만 받는다 — 번역되지 않은
     * {@code DataIntegrityViolationException} 은 여기서 삼키지 않고 그대로 올라가 테스트를 실패시킨다.
     * 삼키면 {@code 500} 누출이 "실패 1건" 으로 세어져 이 테스트가 사고를 통과시킨다.
     *
     * @param 들어갔음     INSERT 를 보낸 직후 내리는 신호 — 상대가 이때부터 착수한다
     * @param 상대를_기다림 상대가 INSERT 에서 잠금을 기다리는 것을 확인하고서야 커밋할지
     */
    private Optional<ErrorCode> 배치한다(long managerId, CountDownLatch 들어갔음, boolean 상대를_기다림) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try {
            return transaction.execute(status -> {
                assignmentCommandService.assign(관계자A(), runId, new AssignmentRequest(managerId, null));
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
     * 상대 세션이 {@code assignment} INSERT 에서 잠금을 기다리는 상태가 될 때까지 커밋을 미룬다.
     *
     * <p>고정 대기({@code Thread.sleep})가 아니라 <b>상태를 물어</b> 기다린다 — 고정 값은 기계 속도에
     * 묶여 느린 기계에서는 순서가 어긋나고 빠른 기계에서는 그만큼 매번 낭비된다. 상한에 걸리면 그대로
     * 커밋하고, 그 경우 뒤의 단언이 실패로 드러낸다.
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
