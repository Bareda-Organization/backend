package src.backend.routing.map.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import src.backend.routing.domain.GeoPoint;
import src.backend.routing.map.spec.RoadLeg;

/**
 * {@code NaverDirectionsGateway.legsOf(List, Summary)} — NCP 가 준 구간별 실측값을 우선 쓰고,
 * 값이 없거나 개수가 안 맞을 때만 {@link StraightLineLegs#distribute} 로 대체하는 분기를 고정한다
 * (R17 T2 목표 1·2·3).
 *
 * <p>실 HTTP 호출 없이 {@code Summary} 를 직접 조립해 검사한다 — 파싱된 이후의 분기 로직만 여기서
 * 보고, 실 응답이 이 형태로 오는지는 {@code NaverDirectionsClientLiveTest} 가 딴다.
 */
class NaverDirectionsGatewayLegsTest {

    /**
     * 목표 2·3 — 구간별 거리를 <b>일부러 직선거리 비율과 다르게</b> 잡는다(지점은 등간격이라 직선거리
     * 비율은 50:50인데, 실측값은 30:170). 그래야 이 시험이 "직선비율로 대체돼도 우연히 같은 값이
     * 나오는" 형태를 피해, 실측값이 실제로 쓰였는지를 가른다.
     *
     * <p>시간(ms)도 1500+1500(각각 따로 반올림하면 2+2=4초)로 잡아, 밀리초를 구간마다 따로 반올림하면
     * 합이 총합(3000ms→3초)을 넘는 상황을 함께 고정한다 — 누적 반올림이 그것을 막는지 본다.
     */
    @Test
    void 실측_구간값이_있으면_그대로_쓰고_합이_총합과_정확히_같다() {
        List<GeoPoint> points = 지점_여러개(3);
        NaverDirectionsGateway.Summary summary = new NaverDirectionsGateway.Summary(
                200, 3000,
                List.of(new NaverDirectionsGateway.WaypointLeg(30, 1500)),
                new NaverDirectionsGateway.GoalLeg(170.0, 1500.0));

        List<RoadLeg> legs = NaverDirectionsGateway.legsOf(points, summary);

        assertThat(legs).hasSize(2);
        assertThat(legs.get(0).distanceMeters())
                .as("직선거리 비율(50:50)이 아니라 실측값(30:170)을 그대로 써야 한다")
                .isEqualTo(30);
        assertThat(legs.get(0).durationSeconds())
                .as("누적 반올림 — 1500ms 까지 누적한 값을 반올림하면 2초다")
                .isEqualTo(2);
        assertThat(legs.get(1).distanceMeters()).isEqualTo(170);
        assertThat(legs.get(1).durationSeconds())
                .as("누적 반올림 — 3000ms 까지 누적한 값(3초)에서 앞 구간에 배정한 2초를 뺀 값이다. "
                        + "구간마다 따로 반올림하면(round(1500/1000)=2 를 두 번) 합이 4초가 되어 총합과 벌어진다")
                .isEqualTo(1);
        assertThat(legs.stream().mapToInt(RoadLeg::distanceMeters).sum()).isEqualTo(200);
        assertThat(legs.stream().mapToInt(RoadLeg::durationSeconds).sum())
                .as("구간별 합이 총합(3000ms → 3초)과 정확히 같아야 한다")
                .isEqualTo(3);
    }

    /**
     * 목표 3 — 구간이 <b>셋 이상</b>일 때 중간 구간도 누적 반올림으로 정확한지 본다. 구간이 둘뿐이면
     * (경유지 1개) 마지막 구간이 "총합 − 이미 배정한 값"으로 강제로 맞춰지므로, 첫 구간을 구간마다
     * 따로 반올림해도 우연히 값이 같아 이 결함을 못 잡는다(1500ms 값 하나만으로는 누적·개별 반올림이
     * 똑같다) — 구간이 셋이어야 <b>중간</b> 구간에서 누적과 개별 반올림이 갈라진다.
     */
    @Test
    void 구간이_셋_이상이면_중간_구간도_누적_반올림으로_정확하다() {
        List<GeoPoint> points = 지점_여러개(4);
        NaverDirectionsGateway.Summary summary = new NaverDirectionsGateway.Summary(
                300, 4500,
                List.of(new NaverDirectionsGateway.WaypointLeg(100, 1500),
                        new NaverDirectionsGateway.WaypointLeg(100, 1500)),
                new NaverDirectionsGateway.GoalLeg(100.0, 1500.0));

        List<RoadLeg> legs = NaverDirectionsGateway.legsOf(points, summary);

        assertThat(legs).hasSize(3);
        assertThat(legs.stream().map(RoadLeg::durationSeconds).toList())
                .as("구간마다 따로 반올림하면(round(1500/1000)=2 씩) [2,2,1]이 된다 — "
                        + "누적 반올림은 [2,1,2]다(1500→2, 3000→3(-2=1), 4500→round(4.5)=5(-3=2))")
                .containsExactly(2, 1, 2);
        assertThat(legs.stream().mapToInt(RoadLeg::durationSeconds).sum()).isEqualTo(5);
    }

    /**
     * 경유지가 없는 2지점 요청은 {@code summary.waypoints}·{@code goal} 자체가 안 온다
     * (2026-09-19 실 API 확인). 이때는 직선거리 비율 배분({@link StraightLineLegs#distribute})으로
     * 대체하는데, 구간이 하나뿐이라 그 결과는 곧 총합 그대로다.
     */
    @Test
    void 경유지가_없으면_직선비율_배분으로_대체한다() {
        List<GeoPoint> points = 지점_여러개(2);
        NaverDirectionsGateway.Summary summary = new NaverDirectionsGateway.Summary(500, 6000, null, null);

        List<RoadLeg> legs = NaverDirectionsGateway.legsOf(points, summary);

        assertThat(legs).hasSize(1);
        assertThat(legs.getFirst().distanceMeters()).isEqualTo(500);
        assertThat(legs.getFirst().durationSeconds()).isEqualTo(6);
    }

    /**
     * 방어적 대체 — {@code waypoints} 개수가 구간 수와 안 맞으면(응답이 우리 가정과 어긋난 경우)
     * 실측값을 믿지 않고 직선거리 비율로 되돌아간다. 그대로 썼다가는 개수가 안 맞는 자리에서
     * {@code IndexOutOfBoundsException} 이 아니라 <b>조용히 틀린 구간</b>이 만들어진다.
     */
    @Test
    void 실측_구간_수가_안_맞으면_직선비율_배분으로_대체한다() {
        List<GeoPoint> points = 지점_여러개(3);
        // 구간은 2개인데 waypoints 가 2개(1개여야 함) — 개수 불일치.
        NaverDirectionsGateway.Summary summary = new NaverDirectionsGateway.Summary(
                200, 3000,
                List.of(new NaverDirectionsGateway.WaypointLeg(50, 500),
                        new NaverDirectionsGateway.WaypointLeg(50, 500)),
                new NaverDirectionsGateway.GoalLeg(100.0, 2000.0));

        List<RoadLeg> legs = NaverDirectionsGateway.legsOf(points, summary);

        List<RoadLeg> 기대값 = StraightLineLegs.distribute(points, 200, 3);
        assertThat(legs)
                .as("개수가 안 맞는 실측값을 그대로 썼다 — 직선거리 비율 배분으로 대체돼야 한다")
                .containsExactlyElementsOf(기대값);
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
