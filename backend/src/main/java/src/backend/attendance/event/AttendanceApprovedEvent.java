package src.backend.attendance.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import src.backend.attendance.entity.AttendanceException;
import src.backend.global.event.DomainEvent;

/**
 * 결석·휴원 신고 승인 — {@code AttendanceCommandService.approve()}가 상태전이 커밋 후 발행한다.
 * 아직 소비자는 없다(Phase 6 routing이 당일 replan 트리거로 구독 예정).
 */
public record AttendanceApprovedEvent(
        UUID eventId,
        Instant occurredAt,
        Long tenantId,
        Long studentId,
        Long attendanceExceptionId,
        LocalDate targetDate
) implements DomainEvent {

    public static AttendanceApprovedEvent of(AttendanceException e) {
        return new AttendanceApprovedEvent(
                UUID.randomUUID(),
                Instant.now(),
                e.getTenantId(),
                e.getStudentId(),
                e.getId(),
                e.getTargetDate());
    }
}
