package src.backend.notification.dto;

import java.time.LocalDateTime;

import src.backend.notification.domain.NotificationLog;
import src.backend.notification.domain.NotificationType;

public record NotificationResponse(
        Long id,
        Long tenantId,
        Long studentId,
        NotificationType type,
        String message,
        LocalDateTime createdAt) {

    public static NotificationResponse from(NotificationLog n) {
        return new NotificationResponse(
                n.getId(), n.getTenantId(), n.getStudentId(), n.getType(), n.getMessage(), n.getCreatedAt());
    }
}
