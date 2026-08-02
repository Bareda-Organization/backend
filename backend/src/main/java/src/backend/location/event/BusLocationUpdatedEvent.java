package src.backend.location.event;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import src.backend.global.event.DomainEvent;
import src.backend.location.dto.LocationOrigin;

/**
 * 버스 좌표 갱신 — {@code BusLocationCommandService.ingest()}가 저장 직후 발행한다.
 * 학생 단위 {@link LocationUpdatedEvent}와 나란한 미러이며, 관제(관리자 테넌트 토픽)와
 * 학부모(개인 큐) 실시간 push 의 트리거다. 좌표 자체는 이미 {@code BusLocationRepository}에
 * 저장돼 있으므로 이 이벤트는 push 대상을 깨우는 신호 역할만 한다.
 *
 * <p>Kafka 토픽명은 클래스명에서 유도된다({@code KafkaEventPublisher.toTopic}) —
 * {@code BusLocationUpdatedEvent} → {@code bus-location-updated}.
 */
public record BusLocationUpdatedEvent(
        UUID eventId,
        Instant occurredAt,
        Long tenantId,
        Long busId,
        double lat,
        double lng,
        LocationOrigin origin
) implements DomainEvent {

    public static BusLocationUpdatedEvent of(Long tenantId, Long busId, double lat, double lng,
                                             LocationOrigin origin, LocalDateTime recordedAt) {
        return new BusLocationUpdatedEvent(
                UUID.randomUUID(),
                recordedAt.atZone(ZoneId.systemDefault()).toInstant(),
                tenantId,
                busId,
                lat,
                lng,
                origin);
    }
}
