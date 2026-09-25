package src.backend.exception.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import src.backend.exception.entity.EmergencyAlert;
import src.backend.exception.entity.EmergencyType;
import src.backend.exception.repository.EmergencyAlertRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;

/**
 * 비상 확인(§5.16)의 <b>동시성</b>(BR-079) — 같은 팝업을 받은 관계자·메인 관리자가 몇 초 안에 [확인]을
 * 눌러도 성공은 1건이고, 확인자는 먼저 확인한 쪽이어야 한다(나머지는 {@code 409 ALREADY_ACKED}).
 *
 * <p>{@code RunConfirmationConcurrencyTest} 와 같은 기법 — {@code @Transactional} 없이 두 스레드가 각자
 * 커밋하고, 먼저 확인한 쪽은 상대가 끝나거나 행 잠금에 막힐 때까지 커밋을 미룬다. {@code emergency_alert}
 * 는 FK 가 없어(V1) 실재하지 않는 학원·계정 id 로 신고 행 하나만 만들고 그 행만 지운다.
 */
@SpringBootTest
class EmergencyAckConcurrencyTest {

    private static final long TIMEOUT_SECONDS = 20;

    private static final long POLL_INTERVAL_MILLIS = 50;

    @Autowired
    private EmergencyCommandService emergencyCommandService;

    @Autowired
    private EmergencyAlertRepository emergencyAlertRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long alertId;

    @AfterEach
    void 뒷정리한다() {
        if (alertId != null) {
            jdbcTemplate.update("DELETE FROM emergency_alert WHERE id = ?", alertId);
        }
    }

    @Test
    @DisplayName("BR-079 — 두 사람이 동시에 확인해도 성공은 1건이고 확인자는 먼저 확인한 쪽이다")
    void 동시에_확인해도_성공은_한_건이다() throws Exception {
        long academyId = 900_000_000L + System.nanoTime() % 1_000_000;
        OffsetDateTime now = OffsetDateTime.now();
        alertId = emergencyAlertRepository.save(EmergencyAlert.onRaise(academyId, 1L, "비상-동시", 1L,
                ManagerRole.DRIVER, EmergencyType.ACCIDENT, 0, now, now, UUID.randomUUID())).getId();
        AuthUser first = new AuthUser(990_001L, academyId, Role.STAFF, AccountStatus.ACTIVE);
        AuthUser second = new AuthUser(990_002L, academyId, Role.STAFF, AccountStatus.ACTIVE);

        CountDownLatch 첫째가_확인했다 = new CountDownLatch(1);
        CountDownLatch 둘째가_끝났다 = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<?>> 결과;
        try {
            Future<?> 첫째 = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
                emergencyCommandService.ack(first, alertId);
                첫째가_확인했다.countDown();
                상대가_끝나거나_잠금에_막힐_때까지_커밋을_미룬다(둘째가_끝났다);
            }));
            Future<?> 둘째 = pool.submit(() -> {
                try {
                    첫째가_확인했다.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    emergencyCommandService.ack(second, alertId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    둘째가_끝났다.countDown();
                }
            });
            결과 = List.of(첫째, 둘째);
            for (Future<?> future : 결과) {
                try {
                    future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (ExecutionException ignored) {
                    // 아래에서 결과별로 다시 센다
                }
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        long 성공 = 결과.stream().filter(this::성공했다).count();
        assertThat(성공).as("조건부 갱신 없이 확인-후-쓰기면 둘 다 성공한다").isEqualTo(1);
        assertThat(결과.get(1)).as("늦은 쪽은 409 ALREADY_ACKED")
                .satisfies(future -> assertThat(거절_코드(future)).isEqualTo(ErrorCode.ALREADY_ACKED));
        assertThat(jdbcTemplate.queryForObject("SELECT acked_by FROM emergency_alert WHERE id = ?", Long.class,
                alertId)).as("확인자는 먼저 확인한 쪽 그대로다").isEqualTo(first.accountId());
    }

    private boolean 성공했다(Future<?> future) {
        return 거절_코드(future) == null;
    }

    private ErrorCode 거절_코드(Future<?> future) {
        try {
            future.get(0, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof BusinessException business) {
                return business.getErrorCode();
            }
            throw new AssertionError("예상 밖 예외", e.getCause());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private void 상대가_끝나거나_잠금에_막힐_때까지_커밋을_미룬다(CountDownLatch 상대가_끝났다) {
        long 마감 = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < 마감 && 상대가_끝났다.getCount() > 0) {
            // pg_stat_activity 는 트랜잭션 단위로 캐시된다 — RunConfirmationConcurrencyTest 의 같은 줄 주석 참고
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
}
