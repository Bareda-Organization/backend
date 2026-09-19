package src.backend.routing.map.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import src.backend.routing.domain.GeoPoint;
import src.backend.routing.map.spec.CallerPolicy;
import src.backend.routing.map.spec.RoadLeg;
import src.backend.routing.map.spec.RoadRoute;
import src.backend.routing.map.spec.RoadRouteRequest;

/**
 * 구간 분할 이어 붙이기(R15 T1 목표 3, Ruling 309) — 공급자 호출({@link NaverDirectionsGateway})은
 * 가짜로 세운다.
 *
 * <p>실 호출 보호(재시도·서킷·격벽)는 {@code NaverDirectionsResilienceTest} 가 로컬 HTTP 서버로
 * 검증한다. 여기서 보는 것은 <b>여러 세그먼트의 좌표를 이어 붙일 때 경계가 중복되지 않는가</b> 뿐이라,
 * 그 판정에 필요 없는 HTTP 계층을 세우지 않는다.
 */
class NaverDirectionsClientTest {

    private static final int MAX_WAYPOINTS_PER_CALL = 3;

    private final NaverDirectionsGateway gateway = mock(NaverDirectionsGateway.class);

    private final NaverDirectionsClient client = new NaverDirectionsClient(gateway, MAX_WAYPOINTS_PER_CALL);

    /**
     * 5지점 · 상한 3 이면 {@code RoutePointSegments.split} 이 {@code [p0,p1,경계]}·{@code [경계,p3,p4]}
     * 두 세그먼트로 나눈다 — 이웃한 두 세그먼트가 경계 지점을 공유한다(그래야 나중에 legs 개수가
     * 복원된다). 두 세그먼트의 공급자 응답이 <b>각자 경계 좌표를 포함</b>해도(실제로 그렇게 온다 —
     * 요청한 시작/끝 지점 근처로 도로 경로가 맺힌다), 이어 붙인 전체 경로에는 한 번만 남아야 한다.
     */
    @Test
    @DisplayName("구간 분할을 이어 붙여도 이음매 좌표가 1번만 나타난다")
    void 이음매_좌표가_한_번만_나타난다() {
        GeoPoint p0 = point("37.500000", "127.000000");
        GeoPoint p1 = point("37.510000", "127.010000");
        GeoPoint 경계 = point("37.520000", "127.020000");
        GeoPoint p3 = point("37.530000", "127.030000");
        GeoPoint p4 = point("37.540000", "127.040000");
        List<GeoPoint> points = List.of(p0, p1, 경계, p3, p4);

        given(gateway.legsOf(List.of(p0, p1, 경계), Duration.ofSeconds(3))).willReturn(List.of(
                new RoadLeg(500, 60, List.of(p0, p1, 경계)),
                new RoadLeg(500, 60, List.of())));
        given(gateway.legsOf(List.of(경계, p3, p4), Duration.ofSeconds(3))).willReturn(List.of(
                new RoadLeg(500, 60, List.of(경계, p3, p4)),
                new RoadLeg(500, 60, List.of())));

        RoadRoute route = client.route(new RoadRouteRequest(points, Duration.ofSeconds(3), CallerPolicy.BATCH));

        assertThat(route.fallbackUsed()).isFalse();
        assertThat(route.legs()).hasSize(points.size() - 1);
        assertThat(route.roadPath())
                .as("경계 좌표가 두 세그먼트 응답에 각각 실려도 전체 경로에는 한 번만 남아야 한다 — "
                        + "안 그러면 지도에 그 지점이 두 번 찍혀 경로가 겹쳐 보인다")
                .containsExactly(p0, p1, 경계, p3, p4);
    }

    private static GeoPoint point(String lat, String lng) {
        return new GeoPoint(new BigDecimal(lat), new BigDecimal(lng));
    }
}
