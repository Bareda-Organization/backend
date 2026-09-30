package src.backend.manager.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
 * BR-269 — 관계자가 회차를 취소하는 사이에 들어온 매니저 배치는 취소 회차에 남지 않는다.
 *
 * <p>취소가 회차 행을 잠근 채 커밋 전인 순간에 배치 요청이 들어오면, 배치는 그 전 상태(미취소)를 읽는다. 배치가
 * 회차를 잠그지 않으면 취소 커밋 뒤에 그대로 INSERT 해 취소 회차에 배치가 남는다. 배치가 회차를 <b>공유 잠금</b>으로
 * 다시 읽으면 취소 커밋을 기다렸다가 취소된 상태를 보고 {@code 409 RUN_CANCELED} 로 끝난다.
 *
 * <p>순서는 결정적으로 만든다 — 취소 트랜잭션이 잠금을 쥔 채 <b>배치가 실제로 DB 잠금을 기다리는 것을 확인한 뒤</b>
 * 커밋한다({@code AssignmentConcurrencyTest} 와 같은 방식). 실제 커밋을 남기므로 자기 표시가 붙은 회차만 직접 지운다.
 */
@SpringBootTest
class AssignmentCancelRaceTest {

    private static final long STAFF_A_ACCOUNT_ID = 2L;

    private static final long ACADEMY_A_ID = 1L;

    private static final long BUS_A1_ID = 1L;

    private static final long 강기사 = 1L;

    private static final String SERVICE_DATE = "2031-07-10";

    private static final String MARKER = "BR269취소경쟁";

    private static final long TIMEOUT_SECONDS = 20;

    private static final long POLL_INTERVAL_MILLIS = 50;

    @Autowired private AssignmentCommandService assignmentCommandService;
    @Autowired private RunCommandService runCommandService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    private long runId;

    @BeforeEach
    void 대상_회차를_만든다() {
        뒷정리한다();
        runId = runCommandService.add(관계자A(), new RunCreateRequest(BUS_A1_ID, SERVICE_DATE, "to_academy",
                "09:10", MARKER, "바래다학원 A", null)).id();
    }

    @AfterEach
    void 뒷정리한다() {
        jdbcTemplate.update("DELETE FROM assignment WHERE run_id IN (SELECT id FROM run WHERE origin_name = ?)",
                MARKER);
        jdbcTemplate.update("DELETE FROM run WHERE origin_name = ?", MARKER);
    }

    @Test
    @DisplayName("BR-269 — 취소가 회차를 잠근 채 커밋하기 전에 들어온 배치는 취소 커밋 뒤 409 RUN_CANCELED 이고 배치 행이 남지 않는다")
    void 취소와_겹친_배치는_취소_회차에_남지_않는다() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        AtomicReference<Future<Optional<ErrorCode>>> 배치 = new AtomicReference<>();
        Optional<ErrorCode> assignResult;
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                runCommandService.cancel(관계자A(), runId); // 회차 행을 잠그고 취소 표시 — 아직 커밋 전
                배치.set(pool.submit(this::배치한다));
                배치가_DB_잠금을_기다릴_때까지_커밋을_미룬다();
            }); // 여기서 취소가 커밋된다
            assignResult = 배치.get().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        assertThat(assignResult)
                .as("취소 커밋 뒤에 배치가 성공하면 취소 회차에 기사가 배치된다")
                .contains(ErrorCode.RUN_CANCELED);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM assignment WHERE run_id = ?", Integer.class,
                runId))
                .as("취소된 회차에 배치 행이 남았다")
                .isZero();
    }

    private Optional<ErrorCode> 배치한다() {
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> assignmentCommandService
                    .assign(관계자A(), runId, new AssignmentRequest(강기사, null)));
            return Optional.empty();
        } catch (BusinessException e) {
            return Optional.of(e.getErrorCode());
        }
    }

    /** 배치 세션이 잠금 대기에 들어간 것을 물어서 확인한다 — 고정 대기는 기계 속도에 묶인다. 상한에 걸리면 그대로 커밋하고 뒤의 검사가 드러낸다. */
    private void 배치가_DB_잠금을_기다릴_때까지_커밋을_미룬다() {
        long 마감 = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < 마감) {
            // pg_stat_activity 는 트랜잭션 단위로 캐시되므로 매번 비운다(AssignmentConcurrencyTest 실측)
            jdbcTemplate.execute("SELECT pg_stat_clear_snapshot()");
            Integer 대기중 = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                            + "AND wait_event_type = 'Lock'",
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
