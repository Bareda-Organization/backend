package src.backend.schedule.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import src.backend.global.common.ApprovalStatus;
import src.backend.global.event.DomainEvent;
import src.backend.schedule.entity.ScheduleChangeRequest;

/**
 * 등하원 시간 변경 요청의 승인/반려 결과 — {@code ScheduleCommandService.approve()/reject()}가
 * 상태전이 커밋 후 발행한다. 알림 모듈이 {@code NotificationType.SCHEDULE_RESULT}로 학부모에게 전달하고,
 * status가 APPROVED면 routing 모듈이 {@code requestedDate}로 국소 replan 대상을 판단한다(Phase 6e).
 */
public record ScheduleResultEvent(
        UUID eventId,
        Instant occurredAt,
        Long tenantId,
        Long studentId,
        String studentName,
        Long scheduleChangeRequestId,
        ApprovalStatus status,
        LocalDate requestedDate
) implements DomainEvent {

    public static ScheduleResultEvent of(ScheduleChangeRequest request, String studentName) {
        return new ScheduleResultEvent(
                UUID.randomUUID(),
                Instant.now(),
                request.getTenantId(),
                request.getStudentId(),
                studentName,
                request.getId(),
                request.getStatus(),
                request.getRequestedDate());
    }
}
