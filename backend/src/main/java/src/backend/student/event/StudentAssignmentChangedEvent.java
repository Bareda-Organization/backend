package src.backend.student.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import src.backend.global.event.DomainEvent;

/**
 * 학생 배정 버스 변경 — {@code StudentCommandService.updateAssignment()}가 커밋 후 발행한다.
 * routing 모듈이 이전/신규 버스 양쪽의 당일 노선 계획을 국소 replan하는 데 쓴다.
 */
public record StudentAssignmentChangedEvent(
        UUID eventId,
        Instant occurredAt,
        Long tenantId,
        Long studentId,
        Long oldBusId,
        Long newBusId,
        LocalDate serviceDate
) implements DomainEvent {

    public static StudentAssignmentChangedEvent of(Long tenantId, Long studentId, Long oldBusId, Long newBusId,
                                                    LocalDate serviceDate) {
        return new StudentAssignmentChangedEvent(
                UUID.randomUUID(),
                Instant.now(),
                tenantId,
                studentId,
                oldBusId,
                newBusId,
                serviceDate);
    }
}
