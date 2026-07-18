package src.backend.notification.query.spec;

import java.util.List;

import src.backend.global.security.AuthUser;
import src.backend.notification.dto.NotificationResponse;

public interface NotificationQueryService {

    /** 학부모: 자녀(형제자매 포함) 알림함. */
    List<NotificationResponse> getChildrenNotifications(AuthUser parent);

    /** 관리자: 학원 알림 이력. */
    List<NotificationResponse> getTenantNotifications(AuthUser admin, Long tenantId);
}
