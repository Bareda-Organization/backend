package src.backend.notification.infrastructure.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import src.backend.notification.domain.NotificationLog;
import src.backend.notification.infrastructure.spec.NotificationSender;

/**
 * MVP 발송 구현 — 실제 푸시·알림톡 대신 로그로 남긴다(발송 추상화의 기본 구현체).
 */
@Component
public class LogNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(LogNotificationSender.class);

    @Override
    public void send(NotificationLog notification) {
        log.info("[notify] type={} tenantId={} studentId={} message={}",
                notification.getType(), notification.getTenantId(), notification.getStudentId(),
                notification.getMessage());
    }
}
