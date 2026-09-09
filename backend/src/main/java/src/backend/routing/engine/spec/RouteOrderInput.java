package src.backend.routing.engine.spec;

import java.util.List;
import java.util.Objects;

import src.backend.global.common.enums.Direction;
import src.backend.routing.domain.GeoPoint;

/**
 * 순서 최적화 입력 — 회차 1건(버스 1대 · 방향 1개)이 도는 구간 전체를 담는다.
 *
 * <p>{@code origin}·{@code destination} 은 방향에 따라 뜻이 다르다 — 등원은 첫 승차지 이전
 * 기준점에서 학원으로, 하원은 학원에서 마지막 하차지로 간다. 방향 분기를 엔진 안에 넣지 않으려고
 * <b>호출자가 이미 방향에 맞춰 채운 값</b>을 받는다.
 */
public record RouteOrderInput(
        GeoPoint origin,
        GeoPoint destination,
        List<OrderableStop> stops,
        List<FixedStop> fixedStops,
        Direction direction) {

    /** 목록을 복사해 잠그는 이유는 엔진이 계산 도중 입력을 다시 읽기 때문이다 — 호출자가 나중에 원본을 고치면 산출이 입력과 어긋난다. */
    public RouteOrderInput {
        Objects.requireNonNull(origin, "출발지가 없다");
        Objects.requireNonNull(destination, "도착지가 없다");
        Objects.requireNonNull(direction, "방향이 없다");
        stops = List.copyOf(Objects.requireNonNull(stops, "재배열 대상 목록이 없다"));
        fixedStops = List.copyOf(Objects.requireNonNull(fixedStops, "고정 정차지 목록이 없다"));
    }

    /** 산출 순서에 들어갈 자리의 총 수 — 재배열 대상과 고정 정차지를 합친 값이다. */
    public int totalStopCount() {
        return stops.size() + fixedStops.size();
    }
}
