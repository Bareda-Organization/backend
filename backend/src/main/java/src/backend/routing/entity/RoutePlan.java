package src.backend.routing.entity;

import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Weekday;

/**
 * 고정 노선의 수정 가능한 정보 묶음(API_SPEC §5.9) — {@code academyId} 는 여기 없다.
 *
 * <p>소속 학원은 토큰이 정하므로(§1.5) 고쳐 넣을 자리를 두지 않는다.
 *
 * <p><b>{@code null} 은 "바꾸지 않음" 이다</b>({@code SchedulePlan} 과 같은 규약) — PATCH 는 보낸
 * 필드만 반영한다.
 *
 * <p>앞의 셋({@code busId}·{@code weekday}·{@code direction})이
 * {@code uk_route_bus_weekday_direction} 의 유일성 조합이다 — 하나만 고쳐도 조합이 바뀐다.
 *
 * <p>정차 순서({@code route_stop})는 여기 없다 — 저장 위치가 다른 테이블이고 순번 재배치라는 별도
 * 절차를 거치므로, 엔티티 필드 대입과 같은 자리에 두면 두 갱신의 실패 처리가 섞인다.
 */
public record RoutePlan(Long busId, Weekday weekday, Direction direction, String name, Boolean active) {
}
