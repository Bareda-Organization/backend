package src.backend.attendance.dto;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import src.backend.attendance.entity.AttendanceType;

/** 결석·휴원 신고 요청 — 학부모가 본인 자녀에 대해서만 생성할 수 있다. */
public record CreateAttendanceExceptionRequest(
        @NotNull @Schema(example = "1", description = "학생 id(김민준, 요청자의 자녀)") Long studentId,
        @NotNull @Schema(example = "ABSENCE") AttendanceType type,
        @NotNull @Schema(example = "2026-07-21") LocalDate targetDate,
        @Schema(example = "감기 몸살") String reason) {
}
