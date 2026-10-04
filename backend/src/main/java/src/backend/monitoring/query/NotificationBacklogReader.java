package src.backend.monitoring.query;

import java.time.OffsetDateTime;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Component;

/**
 * 적재 뒤 오래 발송을 기다리는 알림 수(API_SPEC §6.18 {@code health[]} {@code notification}) — 서버 단위 값이라 학원 필터가 없다. 다른 모듈이 알림
 * 모듈을 참조하지 않는다는 구조 규칙({@code NotificationModuleIsolationTest}) 때문에 알림 모듈의 저장소를 거치지 않고 읽기 전용으로 표를 센다.
 * {@code ix_notification_log_pending}(대기 행만 담는 부분 인덱스)가 받친다.
 */
@Component
class NotificationBacklogReader {

    @PersistenceContext
    private EntityManager entityManager;

    /** {@code threshold} 이전에 적재되고 아직 발송 대기({@code pending})인 알림 수. */
    long countPendingCreatedBefore(OffsetDateTime threshold) {
        return ((Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM notification_log WHERE push_state = 'pending' AND created_at <= :threshold")
                .setParameter("threshold", threshold).getSingleResult()).longValue();
    }
}
