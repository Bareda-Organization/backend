package src.backend.schedule.dto;

import java.time.LocalDate;
import java.time.LocalTime;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** 등하원 시간 변경 요청 생성 — 학부모가 본인 자녀에 대해서만 생성할 수 있다. */
public record CreateScheduleChangeRequest(
        @NotNull @Schema(example = "1", description = "학생 id(김민준, 요청자의 자녀)") Long studentId,
        @NotNull @Schema(example = "2026-07-21") LocalDate requestedDate,
        @NotNull @Schema(example = "17:30:00") LocalTime requestedTime,
        @Schema(example = "병원 진료 일정") String reason) {
}
