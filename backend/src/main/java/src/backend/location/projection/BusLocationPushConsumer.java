package src.backend.location.projection;

import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import src.backend.global.push.PushTargetResolver;
import src.backend.location.dto.BusLocationView;
import src.backend.location.event.BusLocationUpdatedEvent;

/**
 * Kafka 로 발행된 {@link BusLocationUpdatedEvent}를 소비해 버스 좌표를 실시간 push 한다.
 * 학생 단위 {@link LocationPushConsumer}와 같은 구조다 — 학부모는 인원이 소수·결정적이라
 * 개인 큐(convertAndSendToUser)로 보내고, 관리자는 학원 내 인원이 가변적이라 테넌트 토픽으로
 * 브로드캐스트한다. 개인 큐는 Spring 의 user-destination 라우팅이 "본인 세션에만" 배달을
 * 구조적으로 보장해 별도 구독 인가가 필요 없고, 테넌트 토픽만
 * {@code StompAuthChannelInterceptor}의 SUBSCRIBE 인가 대상이다.
 *
 * <p>토픽 {@code bus-location-updated} 는 {@code KafkaEventPublisher.toTopic} 이
 * 이벤트 클래스명에서 유도하는 이름과 정확히 같아야 한다.
 */
@Component
public class BusLocationPushConsumer {

    private final PushTargetResolver pushTargetResolver;
    private final SimpMessagingTemplate messagingTemplate;

    public BusLocationPushConsumer(PushTargetResolver pushTargetResolver, SimpMessagingTemplate messagingTemplate) {
        this.pushTargetResolver = pushTargetResolver;
        this.messagingTemplate = messagingTemplate;
    }

    @KafkaListener(topics = "bus-location-updated")
    public void onBusLocationUpdated(BusLocationUpdatedEvent event) {
        pushTargetResolver.resolveForBus(event.busId()).ifPresent(targets -> {
            BusLocationView view = toView(event, targets.busName());
            for (Long guardianUserId : targets.guardianUserIds()) {
                messagingTemplate.convertAndSendToUser(String.valueOf(guardianUserId), "/queue/bus-location", view);
            }
            messagingTemplate.convertAndSend("/topic/tenant/" + targets.tenantId() + "/bus-locations", view);
        });
    }

    private BusLocationView toView(BusLocationUpdatedEvent event, String busName) {
        LocalDateTime recordedAt = LocalDateTime.ofInstant(event.occurredAt(), ZoneId.systemDefault());
        return new BusLocationView(event.busId(), busName, event.lat(), event.lng(), recordedAt, event.origin());
    }
}
