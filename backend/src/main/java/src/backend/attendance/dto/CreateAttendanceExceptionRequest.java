package src.backend.attendance.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;
import src.backend.attendance.entity.AttendanceType;

/** 결석·휴원 신고 요청 — 학부모가 본인 자녀에 대해서만 생성할 수 있다. */
public record CreateAttendanceExceptionRequest(
        @NotNull Long studentId,
        @NotNull AttendanceType type,
        @NotNull LocalDate targetDate,
        String reason) {
}
