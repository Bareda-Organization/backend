package src.backend.attendance.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

import src.backend.attendance.entity.AttendanceException;
import src.backend.attendance.entity.AttendanceType;
import src.backend.global.common.ApprovalStatus;

public record AttendanceExceptionResponse(
        Long id,
        Long tenantId,
        Long studentId,
        AttendanceType type,
        LocalDate targetDate,
        String reason,
        ApprovalStatus status,
        Long processedBy,
        LocalDateTime createdAt) {

    public static AttendanceExceptionResponse from(AttendanceException e) {
        return new AttendanceExceptionResponse(
                e.getId(), e.getTenantId(), e.getStudentId(), e.getType(),
                e.getTargetDate(), e.getReason(), e.getStatus(),
                e.getProcessedBy(), e.getCreatedAt());
    }
}
