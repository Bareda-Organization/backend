package src.backend.schedule.dto;

import java.time.LocalDate;
import java.time.LocalTime;

import jakarta.validation.constraints.NotNull;

/** 등하원 시간 변경 요청 생성 — 학부모가 본인 자녀에 대해서만 생성할 수 있다. */
public record CreateScheduleChangeRequest(
        @NotNull Long studentId,
        @NotNull LocalDate requestedDate,
        @NotNull LocalTime requestedTime,
        String reason) {
}
