package src.backend.routing.infrastructure.impl;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.routing.domain.GeoMath;
import src.backend.routing.domain.LatLng;
import src.backend.routing.infrastructure.spec.MapRouteClient;
import src.backend.routing.infrastructure.spec.RouteResult;

/**
 * 네이버 클라우드 플랫폼(NCP) Direction 15 API 어댑터 — {@code routing.provider=naver}(기본값, G6 실키 E2E
 * 검증 완료)로 활성화된다.
 *
 * <p>실키 검증 과정에서 확인된 사항:
 * <ul>
 *   <li>{@code waypoints} 파라미터(경유지만, start/goal 제외) 상한은 실제로 5개 — start+goal 포함 총 7개가
 *       API 1회 호출당 한도다. {@code routing.max-waypoints}(기본 7)를 넘으면 {@link src.backend.routing.command.RoutingCommandService#resolveRoute}가 구간을 나눠 여러 번 호출 후 병합한다.</li>
 *   <li>{@code option} 쿼리파라미터를 명시하지 않으면 NCP 기본값이 {@code traoptimal}이라 이 클래스가
 *       기대하는 {@code trafast} 키가 응답에 없어 파싱이 실패한다 — 반드시 {@code option=trafast}를 명시한다.</li>
 *   <li>같은 정류장에 배정된 학생이 인접해 좌표가 연속 중복되면 NCP가 "출발지와 도착지가 동일합니다" 400을
 *       반환한다 — 호출 전 연속 중복 좌표를 접어서 보내고, 접힌 구간은 실제 이동거리가 0이므로 leg duration도
 *       0으로 되살린다({@link #route} 참조).</li>
 *   <li>waypoints 구분자 {@code '|'}는 URI 예약문자가 아니라 {@code URI.create()}로 직접 파싱하면
 *       {@code URISyntaxException}이 난다 — {@link org.springframework.web.util.UriComponentsBuilder}로
 *       percent-encoding을 거쳐야 한다.</li>
 * </ul>
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

        // 같은 정류장에 배정된 학생이 인접하면 좌표가 연속으로 중복되는데, NCP는 이를 "출발지와 도착지가
        // 동일합니다" 400으로 거부한다(실키 E2E로 확인) — 호출 전 연속 중복 좌표를 접어서 보내고, 접힌
        // 구간은 실제 이동거리가 0이므로 legDuration 0으로 그대로 되살려 원래 인덱스를 맞춘다.
        List<LatLng> deduped = new ArrayList<>();
        List<Boolean> legIsReal = new ArrayList<>(); // size == orderedWaypoints.size()-1
        deduped.add(orderedWaypoints.get(0));
        for (int i = 1; i < orderedWaypoints.size(); i++) {
            LatLng point = orderedWaypoints.get(i);
            boolean isDuplicate = point.equals(orderedWaypoints.get(i - 1));
            legIsReal.add(!isDuplicate);
            if (!isDuplicate) {
                deduped.add(point);
            }
        }
        if (deduped.size() < 2) {
            return new RouteResult(0, 0, legIsReal.stream().map(b -> 0.0).toList(), "[]");
        }

        LatLng start = deduped.get(0);
        LatLng goal = deduped.get(deduped.size() - 1);
        List<LatLng> mid = deduped.subList(1, deduped.size() - 1);

        // waypoints 구분자 '|'는 URI 예약문자가 아니라 URI.create()로 직접 파싱하면 URISyntaxException이
        // 난다 — UriComponentsBuilder.encode()로 percent-encoding을 거쳐야 한다(실키 E2E로 확인된 버그).
        // option 미지정 시 NCP 기본값은 traoptimal이라 trafast 키가 응답에 없어 파싱이 실패한다(실키로 확인) —
        // 아래 NaverRouteWrapper.trafast()와 짝을 맞추려면 반드시 명시해야 한다.
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(ENDPOINT)
                .queryParam("start", start.lng() + "," + start.lat())
                .queryParam("goal", goal.lng() + "," + goal.lat())
                .queryParam("option", "trafast");
        if (!mid.isEmpty()) {
            builder.queryParam("waypoints", mid.stream()
                    .map(p -> p.lng() + "," + p.lat())
                    .collect(Collectors.joining("|")));
        }
        URI uri = builder.build().encode().toUri();

        NaverResponse response = webClient.get()
                .uri(uri)
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

        // NCP 응답은 waypoint별 leg duration을 별도로 주지 않아, 구간 거리 비례로 근사한다(실키 E2E로 확인).
        List<Double> dedupedLegDurations = approximateLegDurations(deduped, totalDurationS);
        List<Double> legDurations = new ArrayList<>();
        int dedupedIdx = 0;
        for (boolean real : legIsReal) {
            legDurations.add(real ? dedupedLegDurations.get(dedupedIdx++) : 0.0);
        }
        String polyline = serializePolyline(route.path());
        return new RouteResult(totalDistanceM, totalDurationS, legDurations, polyline);
    }

    private List<Double> approximateLegDurations(List<LatLng> waypoints, double totalDurationS) {
        List<Double> legHaversineM = new ArrayList<>();
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
