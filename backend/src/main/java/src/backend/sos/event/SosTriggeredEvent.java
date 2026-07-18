package src.backend.sos.event;

import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import src.backend.global.event.DomainEvent;
import src.backend.sos.entity.SosEvent;

/**
 * 학생 SOS 발신 — {@code SosCommandService.trigger()}가 저장 커밋 후 발행한다.
 * 알림 모듈이 SOS 알림(학부모+관리자)을 만드는 데 필요한 최소 정보만 담는다.
 */
public record SosTriggeredEvent(
        UUID eventId,
        Instant occurredAt,
        Long tenantId,
        Long studentId,
        String studentName,
        Long sosEventId,
        Double lat,
        Double lng
) implements DomainEvent {

    public static SosTriggeredEvent of(SosEvent event, String studentName) {
        return new SosTriggeredEvent(
                UUID.randomUUID(),
                event.getOccurredAt().atZone(ZoneId.systemDefault()).toInstant(),
                event.getTenantId(),
                event.getStudentId(),
                studentName,
                event.getId(),
                event.getLat(),
                event.getLng());
    }
}
