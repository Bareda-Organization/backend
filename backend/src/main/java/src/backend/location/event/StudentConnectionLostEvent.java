package src.backend.location.event;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import src.backend.global.event.DomainEvent;

/**
 * WebSocket 연결 끊김 확정 — {@code LocationCommandService.checkOverdueDisconnections()}가
 * 유예시간을 넘긴 학생마다 발행한다. occurredAt 은 끊김이 감지된 시각(disconnectedAt)이다.
 */
public record StudentConnectionLostEvent(
        UUID eventId,
        Instant occurredAt,
        Long tenantId,
        Long studentId,
        String studentName
) implements DomainEvent {

    public static StudentConnectionLostEvent of(Long tenantId, Long studentId, String studentName,
                                                 LocalDateTime disconnectedAt) {
        return new StudentConnectionLostEvent(
                UUID.randomUUID(),
                disconnectedAt.atZone(ZoneId.systemDefault()).toInstant(),
                tenantId,
                studentId,
                studentName);
    }
}
