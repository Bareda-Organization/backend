package src.backend.run.entity;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import src.backend.global.common.enums.Direction;

/**
 * 회차 하나를 만들 때 {@link Run#forSchedule} 에 넘기는 값 묶음 — {@link RunDraft} 와 달리 출발 시각과 확정 시각이 이미 시각(시간대 포함)으로 정해져 있다.
 * {@code confirmAt} 은 {@code depart_time - 30분} 이라는 DB CHECK({@code ck_run_confirm_at})의 대상이라 호출자가 미리 계산해 넘긴다.
 * {@code scheduleId} 가 {@code null} 이면 임시로 추가한 회차다(SCH-03).
 */
public record RunPlan(Long academyId, Long busId, Long scheduleId, LocalDate serviceDate, Direction direction,
        OffsetDateTime departTime, OffsetDateTime confirmAt, String originName, String destinationName,
        Integer estDurationMin) {
}
