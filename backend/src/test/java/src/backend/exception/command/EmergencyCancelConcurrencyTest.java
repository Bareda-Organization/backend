package src.backend.exception.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import src.backend.exception.entity.EmergencyAlert;
import src.backend.exception.entity.EmergencyType;
import src.backend.exception.event.EmergencyCanceledEvent;
import src.backend.exception.repository.EmergencyAlertRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.security.AuthUser;
import src.backend.run.access.RunAssignmentAccess;

/**
 * 비상 취소(§4.14)가 <b>읽은 뒤 먼저 커밋된 확인</b>(§5.16)을 지우지 않는다 — 취소가 엔티티를 읽어 {@code canceled_at} 만 바꿔도
 * 전체 컬럼 UPDATE 라 {@code acked_at} · {@code acked_by} · {@code ack_memo} 를 읽은 시점 값(null)으로 되돌릴 수 있었다.
 * EXC-04 는 취소 신고의 "이력 존치" 를 정했고, 확인 기록도 그 이력이다.
 *
 * <p>읽기와 쓰기 사이에 확인이 커밋되는 순서를 만들려고, 바깥 트랜잭션이 신고를 먼저 읽어 두고({@code cancel} 의 서비스 트랜잭션이
 * 이 바깥 트랜잭션에 합류해 같은 영속성 컨텍스트를 쓴다) 그 사이 다른 스레드의 확인을 커밋시킨다. 배치 판정은 이 시험의 관심 밖이라
 * 대체한다 — {@code emergency_alert} 는 FK 가 없어 실재하지 않는 학원·회차 id 로 행 하나만 만들고 그 행만 지운다.
 */
@SpringBootTest
@RecordApplicationEvents
class EmergencyCancelConcurrencyTest {

    private static final long TIMEOUT_SECONDS = 20;

    @MockitoBean
    private RunAssignmentAccess runAssignmentAccess;

    @Autowired
    private EmergencyCommandService emergencyCommandService;

    @Autowired
    private EmergencyAlertRepository emergencyAlertRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationEvents applicationEvents;

    private Long alertId;

    @AfterEach
    void 뒷정리한다() {
        if (alertId != null) {
            jdbcTemplate.update("DELETE FROM emergency_alert WHERE id = ?", alertId);
        }
    }

    @Test
    @DisplayName("취소가 신고를 읽은 뒤 확인이 먼저 커밋돼도 취소는 확인 기록(acked_by·acked_at·ack_memo)을 지우지 않는다")
    void 읽은_뒤_먼저_커밋된_확인을_취소가_덮어쓰지_않는다() throws Exception {
        long academyId = 900_000_000L + System.nanoTime() % 1_000_000;
        OffsetDateTime now = OffsetDateTime.now();
        alertId = emergencyAlertRepository.save(EmergencyAlert.onRaise(academyId, 1L, "비상-덮어쓰기", 1L,
                ManagerRole.DRIVER, EmergencyType.ACCIDENT, 0, now, now, UUID.randomUUID())).getId();
        AuthUser staff = new AuthUser(990_011L, academyId, Role.STAFF, AccountStatus.ACTIVE);
        AuthUser driver = new AuthUser(990_012L, academyId, Role.DRIVER, AccountStatus.ACTIVE);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
                emergencyAlertRepository.findById(alertId).orElseThrow(); // 확인 전 상태를 읽어 둔다
                try {
                    pool.submit(() -> emergencyCommandService.ack(staff, alertId, "119 신고 완료"))
                            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS); // 확인이 먼저 커밋된다
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
                emergencyCommandService.cancel(driver, 1L, alertId); // 늦게 커밋되는 취소
            });
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        assertThat(jdbcTemplate.queryForMap(
                "SELECT acked_by, acked_at IS NOT NULL AS acked, ack_memo, canceled_at IS NOT NULL AS canceled "
                        + "FROM emergency_alert WHERE id = ?", alertId))
                .as("취소는 canceled_at 만 쓰고 먼저 커밋된 확인 기록을 그대로 둔다")
                .containsEntry("acked_by", staff.accountId()).containsEntry("acked", true)
                .containsEntry("ack_memo", "119 신고 완료").containsEntry("canceled", true);
    }

    @Test
    @DisplayName("읽은 뒤 다른 취소가 먼저 커밋되면 늦은 취소는 최초 취소 시각을 바꾸지 않고 그 결과를 돌려준다")
    void 늦은_취소는_최초_취소_시각을_지킨다() throws Exception {
        long academyId = 900_000_000L + System.nanoTime() % 1_000_000;
        OffsetDateTime now = OffsetDateTime.now();
        alertId = emergencyAlertRepository.save(EmergencyAlert.onRaise(academyId, 1L, "비상-이중취소", 1L,
                ManagerRole.DRIVER, EmergencyType.ACCIDENT, 0, now, now, UUID.randomUUID())).getId();
        AuthUser driver = new AuthUser(990_012L, academyId, Role.DRIVER, AccountStatus.ACTIVE);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
                emergencyAlertRepository.findById(alertId).orElseThrow();
                try {
                    pool.submit(() -> emergencyCommandService.cancel(driver, 1L, alertId))
                            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS); // 첫 취소가 먼저 커밋된다
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
                OffsetDateTime first = jdbcTemplate.queryForObject(
                        "SELECT canceled_at FROM emergency_alert WHERE id = ?", OffsetDateTime.class, alertId);
                assertThat(emergencyCommandService.cancel(driver, 1L, alertId).canceledAt())
                        .as("늦은 취소의 응답은 최초 취소 시각이다").isEqualTo(first);
            });
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        assertThat(applicationEvents.stream(EmergencyCanceledEvent.class).count())
                .as("취소 이벤트(emergency_canceled 방송)는 최초 취소에서만 1건이다 — 늦은 취소는 이벤트를 다시 내지 않는다")
                .isEqualTo(1);
    }
}
