package src.backend.routing.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

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

    /**
     * <b>중간 자리</b> 고정을 재는 별도 배치 — 위 좌표들과 달리 고정 자리를 <b>넘는</b> 구간 뒤집기에
     * 실제 이득이 있다.
     *
     * <p>정차지 셋이 동쪽 끝에 뭉쳐 있고 경유 지점만 출발지 쪽에 홀로 있다. 탐욕 씨앗은
     * {@code 31 → 32 → (고정) → 33} 을 놓는데, 이때 앞 세 자리를 통째로 뒤집으면
     * <b>10,586m 가 줄어든다.</b> 즉 2-opt 가 고정 자리를 넘어 뒤집을 수만 있으면 반드시 뒤집는
     * 배치이고, 그래서 이 좌표라야 "넘지 않는다" 는 조항이 실제로 물린다.
     *
     * <p>첫 자리·마지막 자리 고정 시험만으로는 이 조항을 재지 못한다 — 그 배치들은 고정 자리를 넘는
     * 뒤집기의 이득이 0 이거나 음수라, 조항을 지워도 결과가 그대로다(음성 대조 변형 F 에서 실측).
     */
    private static final GeoPoint WAYPOINT_MID_LINE = point("37.500000", "127.010000");
    private static final GeoPoint FAR_EAST_DESTINATION = point("37.500000", "127.100000");
    private static final int MID_SEQ = 3;

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
        double moved = totalMeters(input, movedTo(order, order.stopCount()));

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

    @Test
    void 중간_자리에_고정한_경유_지점은_옮기는_쪽이_유리해도_그_자리에_남는다() {
        RouteOrderInput input = midLineInput();
        StopOrder order = engine.order(input);

        assertThat(order.sequence().get(MID_SEQ - 1).waypointId())
                .as("중간 자리 고정은 앞뒤 두 구간을 가르므로, 넘어서 뒤집으면 지정이 사라진다")
                .isEqualTo(WAYPOINT_ID);
        assertThat(totalMeters(input, movedTo(order, 1)))
                .as("옮긴 해가 더 짧지 않으면 이 시험은 고정을 검사하지 않는다")
                .isLessThan(totalMeters(input, order));
    }

    /**
     * 고정 자리는 노선을 <b>두 구간으로 가른다</b> — 어느 정차지도 그 자리를 건너 반대편으로 가지
     * 못한다.
     *
     * <p>앞 두 자리의 <b>서로 간</b> 순서는 단언하지 않는다. 이 배치에서 그 둘을 맞바꾸는 이득이
     * 0.0000174m 라 사실상 동점이고, 그 순서는 부동소수 잔차가 정한다 — 거기까지 못 박으면 이
     * 시험이 고정 자리가 아니라 반올림을 재게 된다.
     */
    @Test
    void 고정_자리를_건너서는_어느_정차지도_반대편으로_가지_못한다() {
        List<OrderedStop> sequence = engine.order(midLineInput()).sequence();

        assertThat(sequence.get(MID_SEQ - 1).waypointId())
                .as("경유 지점이 가운데 자리를 지켜야 앞뒤 구간이 갈린다")
                .isEqualTo(WAYPOINT_ID);
        assertThat(stopIdsIn(sequence.subList(0, MID_SEQ - 1)))
                .as("고정 자리를 넘어 뒤집으면 앞 구간이 통째로 뒤로 밀려난다")
                .containsExactlyInAnyOrder(31L, 32L);
        assertThat(stopIdsIn(sequence.subList(MID_SEQ, sequence.size())))
                .as("뒤 구간도 마찬가지다")
                .containsExactly(33L);
    }

    private static List<Long> stopIdsIn(List<OrderedStop> sequence) {
        return sequence.stream().map(OrderedStop::stopId).toList();
    }

    private static RouteOrderInput midLineInput() {
        return new RouteOrderInput(
                ORIGIN,
                FAR_EAST_DESTINATION,
                List.of(
                        new OrderableStop(31L, point("37.500000", "127.070000"), 2),
                        new OrderableStop(32L, point("37.500000", "127.080000"), 1),
                        new OrderableStop(33L, point("37.500000", "127.090000"), 2)),
                List.of(new FixedStop(WAYPOINT_ID, WAYPOINT_MID_LINE, MID_SEQ)),
                Direction.TO_ACADEMY);
    }

    /** 경유 지점만 {@code targetSeq} 자리로 옮긴 대안 — 고정이 없었다면 엔진이 갈 수 있던 방향이다. */
    private static StopOrder movedTo(StopOrder order, int targetSeq) {
        OrderedStop waypoint = order.sequence().stream()
                .filter(stop -> stop.waypointId() != null)
                .findFirst()
                .orElseThrow();
        List<OrderedStop> rest = new ArrayList<>(order.sequence().stream()
                .filter(stop -> stop.waypointId() == null)
                .toList());
        rest.add(targetSeq - 1, waypoint);
        return new StopOrder(IntStream.range(0, rest.size())
                .mapToObj(index -> reseq(rest.get(index), index + 1))
                .toList());
    }

    private static OrderedStop reseq(OrderedStop stop, int seq) {
        return stop.waypointId() == null
                ? OrderedStop.ofStop(stop.stopId(), seq, stop.point())
                : OrderedStop.ofWaypoint(stop.waypointId(), seq, stop.point());
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
