package src.backend.sos.event;

import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import src.backend.global.event.DomainEvent;
import src.backend.sos.entity.SosEvent;

/**
 * SOS 3분 미확인 에스컬레이션 — {@code SosCommandService.escalateOverdue()}가 주기 점검에서
 * OPEN 상태로 남은 이벤트마다 발행한다. occurredAt 은 최초 발신 시각을 그대로 유지해
 * dedupKey 의 "대상일자"가 발신일 기준으로 고정되게 한다.
 */
public record SosEscalatedEvent(
        UUID eventId,
        Instant occurredAt,
        Long tenantId,
        Long studentId,
        Long sosEventId
) implements DomainEvent {

    public static SosEscalatedEvent of(SosEvent event) {
        return new SosEscalatedEvent(
                UUID.randomUUID(),
                event.getOccurredAt().atZone(ZoneId.systemDefault()).toInstant(),
                event.getTenantId(),
                event.getStudentId(),
                event.getId());
    }
}
