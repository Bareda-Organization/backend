package src.backend.schedule.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import src.backend.global.common.ApprovalStatus;
import src.backend.schedule.entity.ScheduleChangeRequest;

public record ScheduleChangeRequestResponse(
        Long id,
        Long tenantId,
        Long studentId,
        LocalDate requestedDate,
        LocalTime requestedTime,
        String reason,
        ApprovalStatus status,
        Long processedBy,
        LocalDateTime createdAt) {

    public static ScheduleChangeRequestResponse from(ScheduleChangeRequest e) {
        return new ScheduleChangeRequestResponse(
                e.getId(), e.getTenantId(), e.getStudentId(), e.getRequestedDate(),
                e.getRequestedTime(), e.getReason(), e.getStatus(),
                e.getProcessedBy(), e.getCreatedAt());
    }
}
