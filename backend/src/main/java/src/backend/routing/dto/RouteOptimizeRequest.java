package src.backend.routing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * 고정 노선 순서 최적화 요청(RTE-09 · Ruling 180).
 *
 * <p><b>기준점을 요청이 주는 것이 이 계약의 판단 지점이다.</b> 엔진은 출발지·도착지를 반드시
 * 요구하는데({@code RouteOrderInput}), 고정 노선에는 그 두 점을 담을 자리가 부재하다 —
 * {@code ERD route} 에 좌표 컬럼이 없고 {@code academy} 에도 없다. 서버가 정차지 중 하나를 골라
 * 기준점으로 삼는 방식을 쓰지 않은 이유는 그 고름이 <b>보이지 않는 정책</b>이 되기 때문이다:
 * 어느 자리를 골랐는지에 따라 산출 순서가 달라지는데 요청·응답 어디에도 그 값이 남지 않는다.
 *
 * <p>등원은 첫 승차지 이전 기준점 → 학원, 하원은 학원 → 마지막 하차지다(ARCHITECTURE §8.2) —
 * 방향에 맞춘 값을 채우는 것은 호출자의 몫이다.
 */
public record RouteOptimizeRequest(
        @NotNull @Valid GeoPointRequest origin,
        @NotNull @Valid GeoPointRequest destination) {
}
