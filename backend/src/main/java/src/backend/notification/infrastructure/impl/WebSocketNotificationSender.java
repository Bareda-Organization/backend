package src.backend.notification.infrastructure.impl;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import src.backend.global.push.PushTargetResolver;
import src.backend.notification.domain.NotificationLog;
import src.backend.notification.dto.NotificationResponse;
import src.backend.notification.infrastructure.spec.NotificationSender;

/**
 * 알림을 WebSocket 개인 큐/테넌트 토픽으로도 병행 push 한다(Phase 3e).
 * {@code LogNotificationSender}와 함께 등록돼(NotificationCommandServiceImpl 이 List로 fan-out)
 * 로그 기록과 실시간 push 둘 다 일어난다. 대상 해석은 {@code location.projection.LocationPushConsumer}와
 * 완전히 같아 {@link PushTargetResolver}를 그대로 재사용한다.
 */
@Component
public class WebSocketNotificationSender implements NotificationSender {

    private final PushTargetResolver pushTargetResolver;
    private final SimpMessagingTemplate messagingTemplate;

    public WebSocketNotificationSender(PushTargetResolver pushTargetResolver,
                                       SimpMessagingTemplate messagingTemplate) {
        this.pushTargetResolver = pushTargetResolver;
        this.messagingTemplate = messagingTemplate;
    }

    @Override
    public void send(NotificationLog notification) {
        pushTargetResolver.resolve(notification.getStudentId()).ifPresent(targets -> {
            NotificationResponse response = NotificationResponse.from(notification);
            if (targets.studentUserId() != null) {
                messagingTemplate.convertAndSendToUser(
                        String.valueOf(targets.studentUserId()), "/queue/notifications", response);
            }
            for (Long guardianUserId : targets.guardianUserIds()) {
                messagingTemplate.convertAndSendToUser(String.valueOf(guardianUserId), "/queue/notifications", response);
            }
            if (targets.driverUserId() != null) {
                messagingTemplate.convertAndSendToUser(
                        String.valueOf(targets.driverUserId()), "/queue/notifications", response);
            }
            messagingTemplate.convertAndSend("/topic/tenant/" + targets.tenantId() + "/notifications", response);
        });
    }
}
