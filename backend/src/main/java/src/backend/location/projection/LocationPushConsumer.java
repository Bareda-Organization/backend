package src.backend.location.projection;

import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import src.backend.global.push.PushTargetResolver;
import src.backend.global.push.PushTargetResolver.PushTargets;
import src.backend.location.dto.LocationView;
import src.backend.location.event.LocationUpdatedEvent;

/**
 * Kafka 로 발행된 {@link LocationUpdatedEvent} 를 소비해 관련자에게 실시간 push 한다(Phase 3 플래그십).
 * 본인·학부모·담당기사는 인원이 소수·결정적이라 개인 큐(convertAndSendToUser)로 보내고,
 * 관리자는 학원 내 인원이 가변적이라 테넌트 토픽으로 브로드캐스트한다 — 개인 큐는 Spring 의
 * user-destination 라우팅이 "본인 세션에만" 배달을 구조적으로 보장하므로 별도 구독 인가가 필요 없고,
 * 테넌트 토픽만 {@code StompAuthChannelInterceptor}의 SUBSCRIBE 인가 대상이다(§Phase 3d).
 */
@Component
public class LocationPushConsumer {

    private final PushTargetResolver pushTargetResolver;
    private final SimpMessagingTemplate messagingTemplate;

    public LocationPushConsumer(PushTargetResolver pushTargetResolver, SimpMessagingTemplate messagingTemplate) {
        this.pushTargetResolver = pushTargetResolver;
        this.messagingTemplate = messagingTemplate;
    }

    @KafkaListener(topics = "location-updated")
    public void onLocationUpdated(LocationUpdatedEvent event) {
        pushTargetResolver.resolve(event.studentId()).ifPresent(targets -> {
            LocationView view = toView(event, targets.studentName());
            if (targets.studentUserId() != null) {
                messagingTemplate.convertAndSendToUser(String.valueOf(targets.studentUserId()), "/queue/location", view);
            }
            for (Long guardianUserId : targets.guardianUserIds()) {
                messagingTemplate.convertAndSendToUser(String.valueOf(guardianUserId), "/queue/location", view);
            }
            if (targets.driverUserId() != null) {
                messagingTemplate.convertAndSendToUser(String.valueOf(targets.driverUserId()), "/queue/location", view);
            }
            messagingTemplate.convertAndSend("/topic/tenant/" + targets.tenantId() + "/location", view);
        });
    }

    private LocationView toView(LocationUpdatedEvent event, String studentName) {
        LocalDateTime recordedAt = LocalDateTime.ofInstant(event.occurredAt(), ZoneId.systemDefault());
        return new LocationView(event.studentId(), studentName, event.lat(), event.lng(), recordedAt, event.origin());
    }
}
