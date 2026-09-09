package src.backend.routing.engine.impl;

import java.util.ArrayList;
import java.util.List;

import src.backend.routing.domain.GeoPoint;
import src.backend.routing.engine.spec.OrderableStop;
import src.backend.routing.engine.spec.RouteOrderInput;

/**
 * 빈 자리를 "지금 위치에서 가장 가까운 곳" 으로 하나씩 채운다 — 2-opt 가 다듬을 초기 해다.
 *
 * <p>거리가 같은 후보가 둘 이상일 때 <b>승하차지 ID 가 작은 쪽</b>을 고른다. 이 규칙이 없으면 같은
 * 입력이 명단 조회 순서에 따라 다른 노선을 내고, 그러면 품질 회귀 판정이 무엇을 재는 값인지 알 수
 * 없게 된다(TECH_DECISIONS §8.5.2).
 */
final class NearestNeighborSeeder {

    private NearestNeighborSeeder() {}

    static void seed(RouteSlots slots, RouteOrderInput input) {
        List<OrderableStop> remaining = new ArrayList<>(input.stops());
        GeoPoint current = input.origin();
        for (int index = 0; index < slots.size(); index++) {
            if (slots.isFixed(index)) {
                current = slots.at(index).point();
                continue;
            }
            OrderableStop nearest = nearestTo(current, remaining);
            remaining.remove(nearest);
            slots.place(index, new Slot(nearest.stopId(), null, nearest.point()));
            current = nearest.point();
        }
    }

    private static OrderableStop nearestTo(GeoPoint from, List<OrderableStop> candidates) {
        OrderableStop nearest = null;
        double nearestMeters = 0d;
        for (OrderableStop candidate : candidates) {
            double meters = from.distanceMetersTo(candidate.point());
            if (isBetter(nearest, nearestMeters, candidate, meters)) {
                nearest = candidate;
                nearestMeters = meters;
            }
        }
        return nearest;
    }

    private static boolean isBetter(
            OrderableStop nearest, double nearestMeters, OrderableStop candidate, double meters) {
        if (nearest == null || meters < nearestMeters) {
            return true;
        }
        return meters == nearestMeters && candidate.stopId() < nearest.stopId();
    }
}
