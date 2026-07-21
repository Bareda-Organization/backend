package src.backend.rideevent.event;

import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import src.backend.global.event.DomainEvent;
import src.backend.rideevent.entity.RideEvent;

/**
 * 보호자 인계완료 — {@code RideEventCommandService.record()}가 HANDOVER 기록 커밋 후 발행한다.
 * 알림 모듈이 HANDOVER_DONE 알림을 만드는 데 필요한 최소 정보만 담는다.
 */
public record HandoverCompletedEvent(
        UUID eventId,
        Instant occurredAt,
        Long tenantId,
        Long studentId,
        String studentName,
        Long busId,
        Long stopId,
        Long rideEventId
) implements DomainEvent {

    public static HandoverCompletedEvent of(RideEvent event, String studentName) {
        return new HandoverCompletedEvent(
                UUID.randomUUID(),
                event.getOccurredAt().atZone(ZoneId.systemDefault()).toInstant(),
                event.getTenantId(),
                event.getStudentId(),
                studentName,
                event.getBusId(),
                event.getStopId(),
                event.getId());
    }
}
