package src.backend.location.event;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import src.backend.global.event.DomainEvent;
import src.backend.location.dto.LocationOrigin;

/**
 * 학생 좌표 갱신 — {@code LocationCommandService.ingest()}가 저장 직후 발행한다.
 * Phase 3 실시간 push(관련자 WebSocket 전달)의 트리거이며, 좌표 자체는 이미
 * {@code LocationRepository}에 저장돼 있으므로 이 이벤트는 push 대상을 깨우는 신호 역할만 한다.
 */
public record LocationUpdatedEvent(
        UUID eventId,
        Instant occurredAt,
        Long tenantId,
        Long studentId,
        double lat,
        double lng,
        LocationOrigin origin
) implements DomainEvent {

    public static LocationUpdatedEvent of(Long tenantId, Long studentId, double lat, double lng,
                                           LocationOrigin origin, LocalDateTime recordedAt) {
        return new LocationUpdatedEvent(
                UUID.randomUUID(),
                recordedAt.atZone(ZoneId.systemDefault()).toInstant(),
                tenantId,
                studentId,
                lat,
                lng,
                origin);
    }
}
