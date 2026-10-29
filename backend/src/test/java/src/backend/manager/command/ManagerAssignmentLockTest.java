package src.backend.manager.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
import src.backend.manager.dto.ManagerRegisterRequest;
import src.backend.manager.dto.ManagerUpdateRequest;
import src.backend.run.command.RunCommandService;
import src.backend.run.dto.RunCreateRequest;

/**
 * 매니저 삭제·역할 변경과 회차 배치가 같은 매니저에 동시에 오면 삭제됐거나 역할이 다른 매니저가 끝나지 않은 회차에 남지 않는다
 * (MGR-04·05 · §5.13·§5.14). 삭제·역할 변경은 "배치 없음" 을 확인한 뒤 쓰고, 배치는 매니저를 읽은 뒤 쓴다 — 서로의 미커밋 쓰기를
 * 보지 못하면 둘 다 통과한다. 둘이 매니저 행 잠금으로 직렬화되는지를 본다.
 *
 * <p>먼저 들어간 쪽은 상대가 실제로 DB 잠금을 기다리는 것(또는 잠금 없이 끝난 것)을 확인한 뒤에야 커밋한다 — 신호만으로 순서를
 * 맞추면 상대가 커밋 뒤에 돌아 경합이 아닌 순차 경로를 시험하게 된다({@code AssignmentConcurrencyTest} 와 같은 방식).
 */
@SpringBootTest
class ManagerAssignmentLockTest {

    private static final long STAFF_A_ACCOUNT_ID = 2L;

    private static final long ACADEMY_A_ID = 1L;

    private static final long BUS_A1_ID = 1L;

    private static final String SERVICE_DATE = "2031-08-12";

    private static final String MARKER = "배치잠금시험";

    private static final long TIMEOUT_SECONDS = 20;

    private static final long POLL_INTERVAL_MILLIS = 50;

    @Autowired
    private AssignmentCommandService assignmentCommandService;

    @Autowired
    private ManagerCommandService managerCommandService;

    @Autowired
    private RunCommandService runCommandService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long runId;

    private long managerId;

    @BeforeEach
    void 회차와_매니저를_만든다() {
        뒷정리한다();
        runId = runCommandService.add(관계자A(), new RunCreateRequest(BUS_A1_ID, SERVICE_DATE, "to_academy", "08:40",
                MARKER, "바래다학원 A", null)).id();
        managerId = managerCommandService.register(관계자A(),
                new ManagerRegisterRequest(MARKER, "010-9900-0777", "driver", null)).id();
    }

    @AfterEach
    void 뒷정리한다() {
        jdbcTemplate.update("DELETE FROM assignment WHERE run_id IN (SELECT id FROM run WHERE origin_name = ?)", MARKER);
        jdbcTemplate.update("DELETE FROM run WHERE origin_name = ?", MARKER);
        jdbcTemplate.update("DELETE FROM manager WHERE name = ?", MARKER);
    }

    @Test
    void 배치가_먼저이면_삭제는_배치를_보고_409_MANAGER_ASSIGNED_로_끝난다() throws Exception {
        Optional<ErrorCode> deletion = 먼저_하고_그동안_끼어든다(this::배치한다,
                () -> managerCommandService.delete(관계자A(), managerId));

        assertThat(deletion).contains(ErrorCode.MANAGER_ASSIGNED);
        assertThat(삭제_시각_있음()).as("삭제는 되돌려져 있어야 한다").isFalse();
        assertThat(배치_행_수()).isEqualTo(1);
    }

    @Test
    void 삭제가_먼저이면_배치는_삭제를_보고_404_MANAGER_NOT_FOUND_로_끝난다() throws Exception {
        Optional<ErrorCode> assignment = 먼저_하고_그동안_끼어든다(() -> managerCommandService.delete(관계자A(), managerId),
                this::배치한다);

        assertThat(assignment).contains(ErrorCode.MANAGER_NOT_FOUND);
        assertThat(삭제_시각_있음()).isTrue();
        assertThat(배치_행_수()).as("삭제된 매니저는 배치되지 않는다").isZero();
    }

    @Test
    void 배치가_먼저이면_역할_변경은_배치를_보고_409_MANAGER_ASSIGNED_로_끝난다() throws Exception {
        Optional<ErrorCode> change = 먼저_하고_그동안_끼어든다(this::배치한다, () -> managerCommandService.update(관계자A(),
                managerId, new ManagerUpdateRequest(null, null, "escort", null)));

        assertThat(change).contains(ErrorCode.MANAGER_ASSIGNED);
        assertThat(jdbcTemplate.queryForObject("SELECT role FROM manager WHERE id = ?", String.class, managerId))
                .isEqualTo("driver");
    }

    @Test
    void 역할_변경이_먼저이면_배치는_바뀐_역할을_보고_404_MANAGER_NOT_FOUND_로_끝난다() throws Exception {
        Optional<ErrorCode> assignment = 먼저_하고_그동안_끼어든다(() -> managerCommandService.update(관계자A(), managerId,
                new ManagerUpdateRequest(null, null, "escort", null)), this::배치한다);

        assertThat(assignment).contains(ErrorCode.MANAGER_NOT_FOUND);
        assertThat(jdbcTemplate.queryForObject("SELECT role FROM manager WHERE id = ?", String.class, managerId))
                .isEqualTo("escort");
        assertThat(배치_행_수()).as("역할이 바뀐 매니저는 기사 자리에 배치되지 않는다").isZero();
    }

    @Test
    void 운행_시작이_먼저이면_배치는_이동_중_회차를_보고_409_RUN_ALREADY_STARTED_로_끝난다() throws Exception {
        Optional<ErrorCode> assignment = 먼저_하고_그동안_끼어든다(() -> jdbcTemplate.update(
                "UPDATE run SET status = 'moving', started_at = now() WHERE id = ?", runId), this::배치한다);

        assertThat(assignment).contains(ErrorCode.RUN_ALREADY_STARTED);
        assertThat(배치_행_수()).as("시작된 회차에는 배치가 늘지 않는다").isZero();
    }

    private void 배치한다() {
        assignmentCommandService.assign(관계자A(), runId, new AssignmentRequest(managerId, null));
    }

    /**
     * {@code first} 를 담은 트랜잭션을 열어 둔 채 {@code interloper} 를 다른 스레드에서 시작하고, 그것이 DB 잠금을 기다리거나 끝나면
     * {@code first} 를 커밋한다.
     *
     * @return {@code interloper} 가 던진 {@link ErrorCode}, 성공이면 빈 값
     */
    private Optional<ErrorCode> 먼저_하고_그동안_끼어든다(Runnable first, Runnable interloper) throws Exception {
        CountDownLatch firstDone = new CountDownLatch(1);
        AtomicBoolean interloperFinished = new AtomicBoolean();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstRun = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(
                    status -> {
                        first.run();
                        firstDone.countDown();
                        상대가_기다리거나_끝날_때까지_커밋을_미룬다(interloperFinished);
                    }));
            Future<Optional<ErrorCode>> interfering = pool.submit(() -> {
                firstDone.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                try {
                    interloper.run();
                    return Optional.<ErrorCode>empty();
                } catch (BusinessException e) {
                    return Optional.of(e.getErrorCode());
                } finally {
                    interloperFinished.set(true);
                }
            });
            firstRun.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return interfering.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    /**
     * 다른 세션이 이 트랜잭션이 쥔 잠금에서 기다리는 상태가 될 때까지(또는 잠금 없이 끝날 때까지) 커밋을 미룬다. 상한에 걸리면 그대로
     * 커밋하고, 뒤의 단언이 실패로 드러낸다.
     */
    private void 상대가_기다리거나_끝날_때까지_커밋을_미룬다(AtomicBoolean interloperFinished) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            // pg_stat_activity 는 트랜잭션 단위로 캐시되므로 매번 비우고 읽는다(AssignmentConcurrencyTest 와 같은 이유)
            jdbcTemplate.execute("SELECT pg_stat_clear_snapshot()");
            Integer waiting = jdbcTemplate.queryForObject("SELECT count(*) FROM pg_stat_activity "
                    + "WHERE datname = current_database() AND wait_event_type = 'Lock'", Integer.class);
            if ((waiting != null && waiting > 0) || interloperFinished.get()) {
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

    private boolean 삭제_시각_있음() {
        return jdbcTemplate.queryForObject("SELECT deleted_at IS NOT NULL FROM manager WHERE id = ?", Boolean.class,
                managerId);
    }

    private int 배치_행_수() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM assignment WHERE run_id = ?", Integer.class, runId);
    }

    private AuthUser 관계자A() {
        return new AuthUser(STAFF_A_ACCOUNT_ID, ACADEMY_A_ID, Role.STAFF, AccountStatus.ACTIVE);
    }
}
