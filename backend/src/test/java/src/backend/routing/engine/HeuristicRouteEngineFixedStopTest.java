package src.backend.routing.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import src.backend.global.common.enums.Direction;
import src.backend.routing.domain.GeoPoint;
import src.backend.routing.engine.impl.HeuristicRouteEngine;
import src.backend.routing.engine.spec.FixedStop;
import src.backend.routing.engine.spec.OrderableStop;
import src.backend.routing.engine.spec.OrderedStop;
import src.backend.routing.engine.spec.RouteEngine;
import src.backend.routing.engine.spec.RouteOrderInput;
import src.backend.routing.engine.spec.StopOrder;

/**
 * 경유 지점(RTE-10)의 순번이 최적화에 <b>먹히지 않는다</b>는 것을 고정한다 (ARCHITECTURE §8.2).
 *
 * <p>여기서 쓰는 좌표는 일부러 <b>고정이 손해가 되도록</b> 배치했다 — 경유 지점을 다른 자리로
 * 옮기면 총 주행거리가 눈에 띄게 줄어드는 형태다. 손해가 아닌 배치로 시험하면 "안 옮겼다" 가
 * "옮길 이유가 없었다" 와 구분되지 않아, 고정을 통째로 무시하는 구현도 통과한다.
 *
 * <p>정차지는 위도 37.500 선을 따라 동쪽으로 늘어서 있고 경유 지점만 그 선 위 도착지 근처에 있다.
 * 거리 계산이 직선(Haversine)이라 사람이 종이에서 검산할 수 있는 형태로 골랐다.
 */
class HeuristicRouteEngineFixedStopTest {

    private static final GeoPoint ORIGIN = point("37.500000", "127.000000");
    private static final GeoPoint DESTINATION = point("37.500000", "127.030000");
    private static final long WAYPOINT_ID = 901L;
    /** 도착지 코앞 — 첫 자리에 고정하면 왕복 detour 가 생겨 명백히 손해다. */
    private static final GeoPoint WAYPOINT_NEAR_DESTINATION = point("37.500000", "127.028000");

    private final RouteEngine engine = new HeuristicRouteEngine();

    @Test
    void 순번_2로_지정한_경유_지점은_산출_2번_자리에_남는다() {
        StopOrder order = engine.order(input(new FixedStop(WAYPOINT_ID, point("37.560000", "127.010000"), 2)));

        assertThat(order.sequence().get(1).waypointId())
                .as("지정 순번을 최적화가 뒤집으면 지정의 의미가 소멸한다")
                .isEqualTo(WAYPOINT_ID);
    }

    @Test
    void 옮기는_쪽이_총_주행거리가_짧아도_경유_지점을_옮기지_않는다() {
        RouteOrderInput input = input(new FixedStop(WAYPOINT_ID, WAYPOINT_NEAR_DESTINATION, 1));
        StopOrder order = engine.order(input);
        double kept = totalMeters(input, order);
        double moved = totalMeters(input, movedToLastSlot(order));

        assertThat(order.sequence().getFirst().waypointId())
                .as("경유 지점이 1번 자리에 남아야 한다")
                .isEqualTo(WAYPOINT_ID);
        assertThat(moved)
                .as("옮긴 해가 더 짧지 않으면 이 시험은 고정을 검사하지 않는다")
                .isLessThan(kept);
    }

    @Test
    void 순번_1에_고정해도_그_자리에_남는다() {
        StopOrder order = engine.order(input(new FixedStop(WAYPOINT_ID, WAYPOINT_NEAR_DESTINATION, 1)));

        assertThat(order.sequence().getFirst().waypointId()).isEqualTo(WAYPOINT_ID);
    }

    @Test
    void 마지막_순번에_고정해도_그_자리에_남는다() {
        int last = 5;
        StopOrder order = engine.order(input(new FixedStop(WAYPOINT_ID, point("37.500000", "127.002000"), last)));

        assertThat(order.sequence().get(last - 1).waypointId()).isEqualTo(WAYPOINT_ID);
    }

    @Test
    void 경유_지점이_둘이면_둘_다_지정한_자리에_남는다() {
        StopOrder order = engine.order(input(
                new FixedStop(WAYPOINT_ID, WAYPOINT_NEAR_DESTINATION, 1),
                new FixedStop(902L, point("37.500000", "127.001000"), 4)));

        assertThat(order.sequence().getFirst().waypointId()).isEqualTo(WAYPOINT_ID);
        assertThat(order.sequence().get(3).waypointId()).isEqualTo(902L);
    }

    @Test
    void 재배열_대상_정차지는_하나도_빠지지_않고_한_번씩만_실린다() {
        StopOrder order = engine.order(input(new FixedStop(WAYPOINT_ID, WAYPOINT_NEAR_DESTINATION, 1)));

        assertThat(order.sequence().stream().map(OrderedStop::stopId).filter(id -> id != null).toList())
                .containsExactlyInAnyOrder(11L, 12L, 13L, 14L);
    }

    @Test
    void 산출_순번은_1부터_빈틈_없이_이어진다() {
        StopOrder order = engine.order(input(new FixedStop(WAYPOINT_ID, WAYPOINT_NEAR_DESTINATION, 3)));

        assertThat(order.sequence().stream().map(OrderedStop::seq).toList())
                .containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void 자리마다_승하차지와_경유_지점_중_하나만_채워진다() {
        StopOrder order = engine.order(input(new FixedStop(WAYPOINT_ID, WAYPOINT_NEAR_DESTINATION, 3)));

        assertThat(order.sequence())
                .allSatisfy(stop -> assertThat(stop.stopId() == null)
                        .as("둘 다 채우거나 둘 다 비우면 저장 단계에서야 드러난다")
                        .isNotEqualTo(stop.waypointId() == null));
    }

    @Test
    void 고정_순번이_자리_수를_넘으면_거절한다() {
        RouteOrderInput input = input(new FixedStop(WAYPOINT_ID, WAYPOINT_NEAR_DESTINATION, 6));

        assertThatThrownBy(() -> engine.order(input))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 고정_순번이_겹치면_거절한다() {
        RouteOrderInput input = input(
                new FixedStop(WAYPOINT_ID, WAYPOINT_NEAR_DESTINATION, 2),
                new FixedStop(902L, point("37.500000", "127.001000"), 2));

        assertThatThrownBy(() -> engine.order(input))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 재배열_대상이_없고_경유_지점만_있어도_그_자리에_남는다() {
        RouteOrderInput input = new RouteOrderInput(
                ORIGIN, DESTINATION, List.of(),
                List.of(new FixedStop(WAYPOINT_ID, WAYPOINT_NEAR_DESTINATION, 1)),
                Direction.TO_ACADEMY);

        assertThat(engine.order(input).sequence())
                .singleElement()
                .satisfies(stop -> assertThat(stop.waypointId()).isEqualTo(WAYPOINT_ID));
    }

    /** 경유 지점만 마지막 자리로 옮긴 대안 — 고정이 없었다면 엔진이 골랐을 방향이다. */
    private static StopOrder movedToLastSlot(StopOrder order) {
        List<OrderedStop> rest = order.sequence().stream()
                .filter(stop -> stop.waypointId() == null)
                .toList();
        List<OrderedStop> moved = new java.util.ArrayList<>();
        for (int index = 0; index < rest.size(); index++) {
            OrderedStop stop = rest.get(index);
            moved.add(OrderedStop.ofStop(stop.stopId(), index + 1, stop.point()));
        }
        OrderedStop waypoint = order.sequence().stream()
                .filter(stop -> stop.waypointId() != null)
                .findFirst()
                .orElseThrow();
        moved.add(OrderedStop.ofWaypoint(waypoint.waypointId(), moved.size() + 1, waypoint.point()));
        return new StopOrder(moved);
    }

    private static double totalMeters(RouteOrderInput input, StopOrder order) {
        return RouteQualityMetrics.of(input, order).totalDistanceMeters();
    }

    private static RouteOrderInput input(FixedStop... fixedStops) {
        return new RouteOrderInput(
                ORIGIN,
                DESTINATION,
                List.of(
                        new OrderableStop(11L, point("37.500000", "127.005000"), 2),
                        new OrderableStop(12L, point("37.500000", "127.010000"), 1),
                        new OrderableStop(13L, point("37.500000", "127.015000"), 3),
                        new OrderableStop(14L, point("37.500000", "127.020000"), 1)),
                List.of(fixedStops),
                Direction.TO_ACADEMY);
    }

    private static GeoPoint point(String lat, String lng) {
        return new GeoPoint(new BigDecimal(lat), new BigDecimal(lng));
    }
}
