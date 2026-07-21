package src.backend.student.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import src.backend.global.event.DomainEvent;

/**
 * 학생 하차지 변경 — {@code StudentCommandService.updateDropoff()}가 커밋 후 발행한다.
 * routing 모듈이 배정 버스의 당일 하원 노선 계획을 국소 replan하는 데 쓴다.
 */
public record StudentDropoffChangedEvent(
        UUID eventId,
        Instant occurredAt,
        Long tenantId,
        Long studentId,
        Long busId,
        LocalDate serviceDate
) implements DomainEvent {

    public static StudentDropoffChangedEvent of(Long tenantId, Long studentId, Long busId, LocalDate serviceDate) {
        return new StudentDropoffChangedEvent(
                UUID.randomUUID(),
                Instant.now(),
                tenantId,
                studentId,
                busId,
                serviceDate);
    }
}
