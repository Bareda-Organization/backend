package src.backend.routing.map.impl;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import src.backend.global.location.GeoPoint;

/** NCP Direction 15 에 요청 한 건을 보내는 자리 — URL 조립, 인증 헤더, 타임아웃 적용을 맡는다. */
final class NaverDrivingRequester {

    private static final String KEY_ID_HEADER = "x-ncp-apigw-api-key-id";

    private static final String KEY_HEADER = "x-ncp-apigw-api-key";

    /** NCP 가 경유지를 가르는 문자 — URI 에 그대로 실을 수 없어 인코딩을 거친다. */
    private static final String WAYPOINT_SEPARATOR = "|";

    private final WebClient webClient;

    private final String baseUrl;

    /** Directions 5 · 15 경로 — {@code max-waypoints} 와 짝으로 바꾼다({@code application.yml} 주석). */
    private final String drivingPath;

    private final String keyId;

    private final String key;

    NaverDrivingRequester(WebClient webClient, String baseUrl, String drivingPath, String keyId, String key) {
        this.webClient = webClient;
        this.baseUrl = baseUrl;
        this.drivingPath = drivingPath;
        this.keyId = keyId;
        this.key = key;
    }

    /** 인접 중복을 걷어낸 지점 목록으로 경로를 요청하고, {@code timeout} 안에 응답을 기다려 돌려준다. */
    NaverDrivingResponse fetch(List<GeoPoint> distinct, Duration timeout) {
        return webClient.get()
                .uri(drivingUri(distinct))
                .header(KEY_ID_HEADER, keyId)
                .header(KEY_HEADER, key)
                .retrieve()
                .bodyToMono(NaverDrivingResponse.class)
                .timeout(timeout)
                .block();
    }

    /**
     * 첫 지점이 {@code start}, 마지막이 {@code goal}, 나머지가 {@code waypoints} 다 — NCP 는
     * <b>경도를 먼저</b> 적는다.
     *
     * <p><b>문자열을 이어 {@code URI.create} 로 넘기면 안 된다</b> — 경유지 구분자 {@code |} 는
     * URI 에 쓸 수 없는 문자라 {@code IllegalArgumentException} 이 나고, 그 예외가 폴백에 삼켜져
     * <b>"경유지가 있는 요청만 조용히 근사값이 되는"</b> 형태로 나타난다(이 저장소가 실제로 그
     * 상태였다). 그것을 막는 것은 {@link UriComponentsBuilder} 로 조립하는 것 자체다.
     *
     * <p>⚠ 부하를 지는 것은 {@code .encode()} 가 <b>아니다</b> — 이 입력에서는 {@code build().toUri()}
     * 와 결과가 같다(spring-web 7.0.8 실측: 둘 다 {@code %7C}). 좌표·호스트 밖의 문자가 섞일 때를
     * 위해 남겨 둔 것이고, {@code |} 처리의 근거로 읽으면 안 된다.
     */
    private URI drivingUri(List<GeoPoint> segment) {
        UriComponentsBuilder url = UriComponentsBuilder.fromUriString(baseUrl)
                .path(drivingPath)
                .queryParam("start", coordinate(segment.getFirst()))
                .queryParam("goal", coordinate(segment.getLast()));
        List<GeoPoint> middle = segment.subList(1, segment.size() - 1);
        if (!middle.isEmpty()) {
            url.queryParam("waypoints", middle.stream()
                    .map(NaverDrivingRequester::coordinate)
                    .collect(Collectors.joining(WAYPOINT_SEPARATOR)));
        }
        return url.build().encode().toUri();
    }

    private static String coordinate(GeoPoint point) {
        return point.lng().toPlainString() + "," + point.lat().toPlainString();
    }
}
