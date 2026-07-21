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
import src.backend.routing.domain.LatLng;
import src.backend.routing.infrastructure.spec.MapRouteClient;
import src.backend.routing.infrastructure.spec.RouteResult;

/**
 * OSRM(Open Source Routing Machine) 공개 데모 서버 어댑터 — 키 불필요, {@code routing.provider=osrm}로
 * 활성화(G6 이후 기본값은 {@link NaverMapRouteClient}로 전환됐고, OSRM은 property 자체가 없을 때의
 * fallback{@code matchIfMissing = true}으로 남는다). 실제 서버(router.project-osrm.org)에 curl 로
 * 실동작을 확인했다(서울 좌표 요청 시 정상 legs/geometry 응답).
 */
@Component
@ConditionalOnProperty(name = "routing.provider", havingValue = "osrm", matchIfMissing = true)
public class OsrmMapRouteClient implements MapRouteClient {

    private final WebClient webClient;
    private final String baseUrl;

    public OsrmMapRouteClient(WebClient webClient, @Value("${routing.osrm.base-url}") String baseUrl) {
        this.webClient = webClient;
        this.baseUrl = baseUrl;
    }

    @Override
    public RouteResult route(List<LatLng> orderedWaypoints) {
        if (orderedWaypoints.size() < 2) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "경로 계산에는 최소 2개 지점이 필요합니다");
        }
        // OSRM 은 lon,lat 순서(위경도 반대)로 세미콜론 구분 — 흔한 실수 지점.
        String coords = orderedWaypoints.stream()
                .map(p -> p.lng() + "," + p.lat())
                .collect(Collectors.joining(";"));
        String url = baseUrl + "/route/v1/driving/" + coords + "?overview=full&geometries=geojson&steps=false";

        OsrmResponse response = webClient.get()
                .uri(URI.create(url))
                .retrieve()
                .bodyToMono(OsrmResponse.class)
                .block();

        if (response == null || response.routes() == null || response.routes().isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "OSRM 경로 조회에 실패했습니다");
        }
        OsrmRoute route = response.routes().get(0);
        List<Double> legDurations = route.legs().stream().map(OsrmLeg::duration).toList();
        String polyline = route.geometry() != null ? serializePolyline(route.geometry().coordinates()) : "[]";
        return new RouteResult(route.distance(), route.duration(), legDurations, polyline);
    }

    /** [[lon,lat],...] 좌표열을 JSON 문자열 그대로 보존 — 별도 인코딩 없이 프론트가 바로 파싱한다. */
    private String serializePolyline(List<List<Double>> coordinates) {
        return coordinates.stream()
                .map(c -> "[" + c.get(0) + "," + c.get(1) + "]")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private record OsrmResponse(String code, List<OsrmRoute> routes) {
    }

    private record OsrmRoute(double distance, double duration, List<OsrmLeg> legs, OsrmGeometry geometry) {
    }

    private record OsrmLeg(double distance, double duration) {
    }

    private record OsrmGeometry(List<List<Double>> coordinates) {
    }
}
