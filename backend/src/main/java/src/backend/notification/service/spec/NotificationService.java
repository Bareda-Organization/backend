package src.backend.notification.service.spec;

import java.time.LocalDate;
import java.util.List;

import src.backend.global.security.AuthUser;
import src.backend.notification.dto.NotificationResponse;
import src.backend.notification.entity.NotificationType;

public interface NotificationService {

    /**
     * dedupKey 로 멱등 발송한다 — 이미 같은 키로 발송됐으면 조용히 무시한다.
     * dedupKey 는 {@link #dedupKey} 로 문서 공식(유형+학생+대상일자+정류장/단계)에 맞춰 만든다.
     */
    void notify(NotificationType type, Long tenantId, Long studentId, String dedupKey, String message);

    /** dedupKey 공식(유형+학생+대상일자+정류장/단계) — 모든 호출부가 같은 형식을 쓰도록 통일한다. */
    static String dedupKey(NotificationType type, Long studentId, LocalDate date, Object stage) {
        return type.name() + ":" + studentId + ":" + date + ":" + stage;
    }

    /** 학부모: 자녀(형제자매 포함) 알림함. */
    List<NotificationResponse> getChildrenNotifications(AuthUser parent);

    /** 관리자: 학원 알림 이력. */
    List<NotificationResponse> getTenantNotifications(AuthUser admin, Long tenantId);
}
