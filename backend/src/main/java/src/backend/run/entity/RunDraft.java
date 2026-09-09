package src.backend.run.entity;

import java.time.LocalDate;
import java.time.LocalTime;

import src.backend.global.common.enums.Direction;

/**
 * 회차 하나를 만들기 위한 입력 묶음 — 일일 생성 배치(SCH-02)와 임시 추가(SCH-03)가 <b>같은 것</b>을
 * 넘긴다.
 *
 * <p>두 경로가 한 입력 타입을 쓰는 것이 요점이다. 갈라 두면 확정 시각 계산·유일성 방어가 두 벌이
 * 되고, 한쪽만 고쳤을 때 양쪽 테스트가 계속 통과한다.
 *
 * <p>{@code scheduleId} 가 {@code null} 인 것이 <b>임시 회차의 유일한 표시</b>다(§5.10 · ERD
 * {@code run.schedule_id} nullable).
 *
 * <p>{@code departTime} 이 {@link LocalTime} 인 것에 유의한다 — 날짜가 없는 시각이고,
 * {@code serviceDate} 와 합쳐 {@code timestamptz} 로 확정하는 것은 {@code RunCommandService} 다.
 * 시간대를 이 record 가 들고 있으면 호출부마다 다른 시간대를 넣을 자리가 생긴다.
 */
public record RunDraft(Long academyId, Long busId, Long scheduleId, LocalDate serviceDate, Direction direction,
        LocalTime departTime, String originName, String destinationName, Integer estDurationMin) {
}
