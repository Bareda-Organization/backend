package src.backend.routing.map.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import src.backend.routing.domain.GeoPoint;
import src.backend.routing.map.spec.RoadLeg;
import src.backend.routing.map.spec.RoadRoute;

/**
 * 총합 배분이 <b>합을 보존</b>하는지, 폴백이 직선거리 그대로인지를 고정한다.
 *
 * <p>배분은 반올림을 구간마다 하므로 순진하게 짜면 구간이 늘수록 합이 총합에서 벌어진다. 그 오차는
 * 노선 전체의 거리·소요 시간을 조용히 바꾸고, 결과 형태는 그대로라 어느 기능 시험에도 드러나지 않는다.
 */
class StraightLineLegsTest {

    @Test
    void 배분한_구간_값의_합이_공급자_총합과_정확히_같다() {
        List<GeoPoint> points = 지점_여러개(9);

        List<RoadLeg> legs = StraightLineLegs.distribute(points, 12345, 6789);

        assertThat(legs).hasSize(points.size() - 1);
        assertThat(legs.stream().mapToInt(RoadLeg::distanceMeters).sum()).isEqualTo(12345);
        assertThat(legs.stream().mapToInt(RoadLeg::durationSeconds).sum()).isEqualTo(6789);
    }

    /** 모든 지점이 같은 자리면 비율의 분모가 0이 된다 — 0으로 나누지 않고 균등 배분한다. */
    @Test
    void 지점이_전부_같은_자리여도_총합을_나눠_담는다() {
        GeoPoint 한_자리 = new GeoPoint(new BigDecimal("37.500000"), new BigDecimal("127.000000"));

        List<RoadLeg> legs = StraightLineLegs.distribute(List.of(한_자리, 한_자리, 한_자리), 300, 60);

        assertThat(legs).hasSize(2);
        assertThat(legs.stream().mapToInt(RoadLeg::distanceMeters).sum()).isEqualTo(300);
    }

    /** 폴백 거리는 직선거리 그대로이고, 소요 시간은 거리가 늘면 함께 는다. */
    @Test
    void 폴백은_직선거리와_그에_비례한_시간을_낸다() {
        List<GeoPoint> points = 지점_여러개(3);

        RoadRoute route = StraightLineLegs.approximate(points);

        assertThat(route.fallbackUsed()).isTrue();
        assertThat(route.legs()).hasSize(2);
        assertThat(route.legs().getFirst().distanceMeters())
                .isEqualTo((int) Math.round(points.get(0).distanceMetersTo(points.get(1))));
        assertThat(route.legs().getFirst().durationSeconds()).isPositive();
    }

    private static List<GeoPoint> 지점_여러개(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> new GeoPoint(
                        new BigDecimal("37.500000").add(new BigDecimal("0.010000").multiply(BigDecimal.valueOf(i))),
                        new BigDecimal("127.000000")))
                .map(GeoPoint.class::cast)
                .toList();
    }
}
