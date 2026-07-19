package src.backend.routing.engine.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import src.backend.routing.domain.GeoMath;
import src.backend.routing.domain.LatLng;

/**
 * sweep+NN+2-opt 휴리스틱 단위 테스트 — Spring 컨텍스트 없이 순수 알고리즘만 검증한다.
 */
class HeuristicRouteEngineTest {

    private final HeuristicRouteEngine engine = new HeuristicRouteEngine();
    private final LatLng depot = new LatLng(37.5075, 127.0355);   // 한빛학원 depot(시드 좌표)

    @Test
    void emptyInput_returnsEmptyList() {
        assertTrue(engine.optimizeOrder(depot, Map.of()).isEmpty());
    }

    @Test
    void singlePoint_returnsThatId() {
        Map<Long, LatLng> points = Map.of(1L, new LatLng(37.50, 127.03));

        assertEquals(List.of(1L), engine.optimizeOrder(depot, points));
    }

    @Test
    void optimizeOrder_returnsPermutationOfAllIds() {
        Map<Long, LatLng> points = Map.of(
                1L, new LatLng(37.4998, 127.0245),
                2L, new LatLng(37.5032, 127.0398),
                3L, new LatLng(37.5060, 127.0290),
                4L, new LatLng(37.4950, 127.0410));

        List<Long> result = engine.optimizeOrder(depot, points);

        assertEquals(Set.of(1L, 2L, 3L, 4L), Set.copyOf(result));
        assertEquals(4, result.size());
    }

    @Test
    void optimizeOrder_totalDistanceNotWorseThanNaiveZigzagOrder() {
        // 삽입 순서 그대로 방문하면 남서→동→(먼)북서→중앙 으로 지그재그가 되도록 배치 — 2-opt 개선 여지 확보.
        Map<Long, LatLng> points = new LinkedHashMap<>();
        points.put(1L, new LatLng(37.4998, 127.0245));
        points.put(2L, new LatLng(37.5032, 127.0398));
        points.put(3L, new LatLng(37.5150, 127.0250));
        points.put(4L, new LatLng(37.5060, 127.0290));

        List<Long> naiveOrder = List.copyOf(points.keySet());
        double naiveDistance = totalDistance(points, naiveOrder);

        List<Long> optimized = engine.optimizeOrder(depot, points);
        double optimizedDistance = totalDistance(points, optimized);

        assertTrue(optimizedDistance <= naiveDistance,
                "optimized=" + optimizedDistance + " naive=" + naiveDistance);
    }

    private double totalDistance(Map<Long, LatLng> points, List<Long> order) {
        double sum = 0;
        LatLng current = depot;
        for (Long id : order) {
            LatLng next = points.get(id);
            sum += GeoMath.distanceMeters(current, next);
            current = next;
        }
        return sum;
    }
}
