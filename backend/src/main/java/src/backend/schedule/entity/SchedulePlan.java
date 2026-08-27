package src.backend.schedule.entity;

import java.time.LocalTime;

import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Weekday;

/**
 * 스케줄의 수정 가능한 정보 묶음(API_SPEC §5.10) — {@code academyId} 는 여기 없다.
 *
 * <p>소속 학원은 토큰이 정하므로(§1.5) 고쳐 넣을 자리를 두지 않는다.
 *
 * <p><b>{@code null} 은 "바꾸지 않음" 이다</b>({@code ManagerProfile} 과 같은 규약) — PATCH 는 보낸
 * 필드만 반영한다.
 *
 * <p>앞의 넷({@code busId}·{@code weekday}·{@code direction}·{@code departTime})이
 * {@code uk_schedule_bus_weekday_direction_depart} 의 유일성 조합이다 — 하나만 고쳐도 조합이 바뀐다.
 */
public record SchedulePlan(Long busId, Weekday weekday, Direction direction, LocalTime departTime,
        String originName, String destinationName, Integer estDurationMin, Boolean active) {
}
