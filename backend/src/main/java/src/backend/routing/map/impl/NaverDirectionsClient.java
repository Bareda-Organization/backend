package src.backend.routing.map.impl;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import src.backend.routing.domain.GeoPoint;
import src.backend.routing.map.spec.CallerPolicy;
import src.backend.routing.map.spec.MapRouteClient;
import src.backend.routing.map.spec.MapRouteUnavailableException;
import src.backend.routing.map.spec.RoadLeg;
import src.backend.routing.map.spec.RoadRoute;
import src.backend.routing.map.spec.RoadRouteRequest;

/**
 * 네이버 Directions 어댑터 — 경유지 상한을 넘는 지점열을 나눠 부르고, 실패를 폴백·오류로 가른다.
 *
 * <p>공급자 호출 자체는 {@link NaverDirectionsGateway} 가 맡는다. 여기서 부르면 Spring AOP 프록시를
 * 거치지 않아 재시도·서킷이 걸리지 않기 때문이다.
 */
@Component
@ConditionalOnProperty(name = "app.routing.map.provider", havingValue = "naver", matchIfMissing = true)
public class NaverDirectionsClient implements MapRouteClient {

    private final NaverDirectionsGateway gateway;

    private final int maxWaypoints;

    public NaverDirectionsClient(NaverDirectionsGateway gateway,
            @Value("${app.routing.map.max-waypoints}") int maxWaypoints) {
        this.gateway = gateway;
        this.maxWaypoints = maxWaypoints;
    }

    /**
     * 구간을 나눠 부른 결과를 하나로 이어 붙인다 — <b>호출자는 분할이 일어났는지 모른다.</b>
     *
     * <p>한 구간이라도 실패하면 <b>경로 전체</b>를 직선거리 근사로 되돌린다. 성공한 구간만 실측값으로
     * 남기면 절반은 도로 값 · 절반은 근사값인 경로가 되고, {@code fallbackUsed} 한 칸으로는 그 상태를
     * 서술할 수단이 부재하다.
     */
    @Override
    public RoadRoute route(RoadRouteRequest request) {
        try {
            return new RoadRoute(callSegments(request), false);
        } catch (MapRouteUnavailableException e) {
            if (e.isCircuitOpen() && request.caller() == CallerPolicy.ON_DEMAND) {
                throw e;
            }
            return StraightLineLegs.approximate(request.points());
        }
    }

    private List<RoadLeg> callSegments(RoadRouteRequest request) {
        List<RoadLeg> legs = new ArrayList<>();
        for (List<GeoPoint> segment : RoutePointSegments.split(request.points(), maxWaypoints)) {
            legs.addAll(gateway.legsOf(segment, request.timeout()));
        }
        return legs;
    }
}
