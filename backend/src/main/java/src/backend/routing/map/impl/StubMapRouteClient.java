package src.backend.routing.map.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import src.backend.routing.domain.GeoPoint;
import src.backend.routing.map.spec.MapRouteClient;
import src.backend.routing.map.spec.RoadLeg;
import src.backend.routing.map.spec.RoadRoute;
import src.backend.routing.map.spec.RoadRouteRequest;

/**
 * 테스트·오프라인용 결정론적 도로 경로 — 같은 지점열은 <b>언제나</b> 같은 구간 값을 낸다.
 *
 * <p>난수나 시각을 섞지 않는 이유는 상위 단계의 단언 때문이다. 값이 실행마다 갈리면 "이 순서가 저
 * 순서보다 짧다"(품질 회귀 판정)와 도착 예정 시각 단언이 어느 날은 참이고 어느 날은 거짓이 되어,
 * 그 초록이 코드에 대해 아무것도 말하지 않는다(Ruling 157).
 *
 * <h2>계약 — 두 갈래만 있다</h2>
 *
 * <ul>
 *   <li><b>{@link #UNAVAILABLE_MARKER_LAT} 를 가진 지점이 하나라도 있으면 공급자 장애</b> — 직선거리
 *       근사를 {@code fallbackUsed=true} 로 돌려준다. 이 입력이 없으면 상위 단계가
 *       {@code ComputationSnapshot.fallbackUsed} 를 검사할 수단이 부재해, 그 값을 항상 {@code false}
 *       로 싣는 구현이 그대로 통과한다</li>
 *   <li><b>그 밖에는 정상 응답</b> — 직선거리에 {@link #ROAD_DETOUR_FACTOR} 를 곱한 값이다.
 *       폴백과 <b>수치가 달라야</b> 한다. 같으면 {@code fallbackUsed} 만 다르고 거리·시간이 같아,
 *       폴백 경로를 실제 경로로 오인해 저장하는 구현이 드러나지 않는다</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "app.routing.map.provider", havingValue = "stub")
public class StubMapRouteClient implements MapRouteClient {

    /**
     * 이 위도를 가진 지점이 들어오면 공급자 장애로 답한다 — 적도 부근이라 이 서비스의 실 좌표
     * (북위 33~39도)와 겹치지 않아 오탐이 부재하다.
     *
     * <p>상수로 노출하는 것은 테스트가 이 값을 손으로 옮겨 적지 않게 하기 위함이다.
     */
    public static final BigDecimal UNAVAILABLE_MARKER_LAT = new BigDecimal("1.000000");

    /** 실 도로가 직선보다 도는 정도 — 스텁 값을 폴백 값과 구별되게 만드는 것이 목적이다. */
    private static final double ROAD_DETOUR_FACTOR = 1.3d;

    /** 스텁이 가정하는 주행 속도(km/h) — 폴백의 실효 속도와 달라야 두 경로의 소요 시간이 갈린다. */
    private static final int STUB_SPEED_KMH = 30;

    private static final double SECONDS_PER_HOUR = 3600d;

    private static final double METERS_PER_KILOMETER = 1000d;

    @Override
    public RoadRoute route(RoadRouteRequest request) {
        if (hasUnavailableMarker(request.points())) {
            return StraightLineLegs.approximate(request.points());
        }
        List<GeoPoint> points = request.points();
        List<RoadLeg> legs = new ArrayList<>(points.size() - 1);
        for (int i = 0; i < points.size() - 1; i++) {
            int meters = (int) Math.round(points.get(i).distanceMetersTo(points.get(i + 1)) * ROAD_DETOUR_FACTOR);
            legs.add(new RoadLeg(meters, secondsFor(meters)));
        }
        return new RoadRoute(legs, false);
    }

    private static boolean hasUnavailableMarker(List<GeoPoint> points) {
        return points.stream().anyMatch(point -> point.lat().compareTo(UNAVAILABLE_MARKER_LAT) == 0);
    }

    private static int secondsFor(int meters) {
        return (int) Math.round(meters * SECONDS_PER_HOUR / (STUB_SPEED_KMH * METERS_PER_KILOMETER));
    }
}
