package src.backend.routing.infrastructure.impl;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.routing.domain.GeoMath;
import src.backend.routing.domain.LatLng;
import src.backend.routing.infrastructure.spec.MapRouteClient;
import src.backend.routing.infrastructure.spec.RouteResult;

/**
 * 네이버 클라우드 플랫폼(NCP) Direction 15 API 어댑터 — 정식 전환용, {@code routing.provider=naver}로 활성화.
 *
 * <p>⚠️ 이 프로젝트엔 실제 NCP 키가 없어(.env.example 에 빈 값만 예약) E2E 로 검증하지 못했다.
 * NCP Direction 15 API 문서 스펙대로 구현했으나 실키 없이는 응답 파싱 정확성을 보장할 수 없다 —
 * 기본값은 {@code routing.provider=osrm} 이라 이 어댑터가 없어도 6d 는 정상 동작한다.
 * NCP Direction 15 는 waypoint 총합(출발+도착+경유) 5개 제한이 있어 실사용 시 6d 의 15개 청킹
 * 상한과 별도로 재검토가 필요하다.
 */
@Component
@ConditionalOnProperty(name = "routing.provider", havingValue = "naver")
public class NaverMapRouteClient implements MapRouteClient {

    private static final String ENDPOINT = "https://maps.apigw.ntruss.com/map-direction/v1/driving";

    private final WebClient webClient;
    private final String keyId;
    private final String key;

    public NaverMapRouteClient(WebClient webClient,
                               @Value("${routing.naver.key-id}") String keyId,
                               @Value("${routing.naver.key}") String key) {
        this.webClient = webClient;
        this.keyId = keyId;
        this.key = key;
    }

    @Override
    public RouteResult route(List<LatLng> orderedWaypoints) {
        if (orderedWaypoints.size() < 2) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "경로 계산에는 최소 2개 지점이 필요합니다");
        }
        LatLng start = orderedWaypoints.get(0);
        LatLng goal = orderedWaypoints.get(orderedWaypoints.size() - 1);
        List<LatLng> mid = orderedWaypoints.subList(1, orderedWaypoints.size() - 1);

        StringBuilder url = new StringBuilder(ENDPOINT)
                .append("?start=").append(start.lng()).append(',').append(start.lat())
                .append("&goal=").append(goal.lng()).append(',').append(goal.lat());
        if (!mid.isEmpty()) {
            url.append("&waypoints=").append(mid.stream()
                    .map(p -> p.lng() + "," + p.lat())
                    .collect(Collectors.joining("|")));
        }

        NaverResponse response = webClient.get()
                .uri(URI.create(url.toString()))
                .header("X-NCP-APIGW-API-KEY-ID", keyId)
                .header("X-NCP-APIGW-API-KEY", key)
                .retrieve()
                .bodyToMono(NaverResponse.class)
                .block();

        if (response == null || response.route() == null || response.route().trafast() == null
                || response.route().trafast().isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "네이버 경로 조회에 실패했습니다");
        }
        NaverRoute route = response.route().trafast().get(0);
        double totalDurationS = route.summary().duration() / 1000.0;   // NCP 는 ms 단위
        double totalDistanceM = route.summary().distance();

        // NCP 응답은 waypoint별 leg duration을 별도로 주지 않아, 구간 거리 비례로 근사한다
        // (실키 미보유로 정밀 검증 불가 — routing.provider=osrm 기본값이라 6d 정상동작엔 영향 없음).
        List<Double> legDurations = approximateLegDurations(orderedWaypoints, totalDurationS);
        String polyline = serializePolyline(route.path());
        return new RouteResult(totalDistanceM, totalDurationS, legDurations, polyline);
    }

    private List<Double> approximateLegDurations(List<LatLng> waypoints, double totalDurationS) {
        List<Double> legHaversineM = new java.util.ArrayList<>();
        double sum = 0;
        for (int i = 0; i < waypoints.size() - 1; i++) {
            double d = GeoMath.distanceMeters(waypoints.get(i), waypoints.get(i + 1));
            legHaversineM.add(d);
            sum += d;
        }
        double finalSum = sum == 0 ? 1 : sum;
        return legHaversineM.stream().map(d -> totalDurationS * d / finalSum).toList();
    }

    private String serializePolyline(List<List<Double>> path) {
        if (path == null) {
            return "[]";
        }
        return path.stream()
                .map(c -> "[" + c.get(0) + "," + c.get(1) + "]")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private record NaverResponse(NaverRouteWrapper route) {
    }

    private record NaverRouteWrapper(List<NaverRoute> trafast) {
    }

    private record NaverRoute(NaverSummary summary, List<List<Double>> path) {
    }

    private record NaverSummary(double distance, double duration) {
    }
}
