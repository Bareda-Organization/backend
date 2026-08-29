package src.backend.routing.engine.impl;

import src.backend.routing.domain.GeoPoint;
import src.backend.routing.engine.spec.RouteOrderInput;

/**
 * 자리표 하나를 거리로 환산한다 — 출발지와 도착지를 자리 바깥의 양 끝으로 다룬다.
 *
 * <p>바깥 두 점을 자리표에 넣지 않고 여기서만 잇는 이유는, 넣으면 그 둘도 재배열·뒤집기 후보가
 * 되어 매 단계마다 "이 자리는 건드리면 안 된다" 를 다시 확인하게 되기 때문이다.
 */
final class TourCost {

    private TourCost() {}

    /** {@code index} 가 자리 범위를 벗어나면 출발지·도착지를 돌려준다. */
    static GeoPoint pointAt(RouteSlots slots, RouteOrderInput input, int index) {
        if (index < 0) {
            return input.origin();
        }
        if (index >= slots.size()) {
            return input.destination();
        }
        return slots.at(index).point();
    }

    /** 출발지에서 도착지까지 전 구간 직선거리 합(m). */
    static double totalMeters(RouteSlots slots, RouteOrderInput input) {
        double meters = 0d;
        for (int index = -1; index < slots.size(); index++) {
            meters += pointAt(slots, input, index)
                    .distanceMetersTo(pointAt(slots, input, index + 1));
        }
        return meters;
    }
}
