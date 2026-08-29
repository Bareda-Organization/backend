package src.backend.routing.map.impl;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.netty.channel.ChannelOption;
import reactor.netty.http.client.HttpClient;

import src.backend.routing.domain.GeoPoint;
import src.backend.routing.map.spec.MapRouteUnavailableException;
import src.backend.routing.map.spec.RoadLeg;

/**
 * NCP Direction 5 를 <b>구간 하나</b>만큼 부르고 보호를 거는 자리 — 재시도·서킷이 걸리는 유일한 지점이다.
 *
 * <p>{@link NaverDirectionsClient} 와 빈을 가르는 이유는 Spring AOP 때문이다. 같은 클래스 안에서
 * 부르면 프록시를 거치지 않아 {@code @Retry}·{@code @CircuitBreaker} 가 <b>한 번도 걸리지 않고</b>,
 * 설정에는 값이 적혀 있으니 아무도 눈치채지 못한다.
 */
@Component
@ConditionalOnProperty(name = "app.routing.map.provider", havingValue = "naver", matchIfMissing = true)
public class NaverDirectionsGateway {

    /** {@code resilience4j.*.instances} 의 키 — 재시도·서킷이 같은 이름을 공유한다. */
    public static final String RESILIENCE_INSTANCE = "mapRoute";

    private static final String DRIVING_PATH = "/map-direction/v1/driving";

    private static final String KEY_ID_HEADER = "x-ncp-apigw-api-key-id";

    private static final String KEY_HEADER = "x-ncp-apigw-api-key";

    /** 연결 자체가 안 되는 경우의 상한 — 응답 대기 상한은 호출자가 요청마다 주입한다. */
    private static final int CONNECT_TIMEOUT_MILLIS = 3000;

    /** NCP 는 소요 시간을 밀리초로 준다. */
    private static final int MILLIS_PER_SECOND = 1000;

    private final WebClient webClient;

    private final String baseUrl;

    private final String keyId;

    private final String key;

    /**
     * 공유 {@code webClient} 빈을 쓰지 않고 여기서 따로 만든다 — 그쪽은 응답 타임아웃이 5초로
     * 고정돼 있어, 배치가 주입한 긴 타임아웃이 조용히 5초로 잘린다. 주입한 값과 실제로 기다리는
     * 값이 갈리면 {@code ARCHITECTURE §8.3} 의 "배치는 길게" 가 설정에만 남는다.
     */
    public NaverDirectionsGateway(WebClient.Builder builder,
            @Value("${app.routing.map.naver.base-url}") String baseUrl,
            @Value("${app.routing.map.naver.key-id:}") String keyId,
            @Value("${app.routing.map.naver.key:}") String key) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS);
        this.webClient = builder.clientConnector(new ReactorClientHttpConnector(httpClient)).build();
        this.baseUrl = baseUrl;
        this.keyId = keyId;
        this.key = key;
    }

    /**
     * 구간 하나의 도로 값을 {@code segment.size() - 1} 개로 돌려준다.
     *
     * <p><b>{@code fallbackMethod} 가 {@code @Retry} 쪽에 있어야 한다.</b> 두 애스펙트의 순서는
     * Retry 가 바깥 · CircuitBreaker 가 안쪽으로 고정돼 있다. 안쪽에 fallback 을 걸면 첫 실패를
     * 안쪽이 {@link MapRouteUnavailableException} 으로 삼켜 바깥 Retry 에게는 <b>성공한 호출</b>로
     * 보이고, {@code max-attempts} 를 적어 둔 채로 실제 호출은 한 번만 나간다 — Phase 5 가 실제로
     * 밟은 형태다. {@code NaverDirectionsResilienceTest} 가 도달한 호출 수를 세어 이 순서를 고정한다.
     *
     * @throws MapRouteUnavailableException 공급자에 닿지 못한 전부 — 타임아웃 · 5xx · 서킷 개방
     */
    @CircuitBreaker(name = RESILIENCE_INSTANCE)
    @Retry(name = RESILIENCE_INSTANCE, fallbackMethod = "unavailable")
    public List<RoadLeg> legsOf(List<GeoPoint> segment, Duration timeout) {
        DrivingResponse response = webClient.get()
                .uri(drivingUri(segment))
                .header(KEY_ID_HEADER, keyId)
                .header(KEY_HEADER, key)
                .retrieve()
                .bodyToMono(DrivingResponse.class)
                .timeout(timeout)
                .block();
        Summary summary = summaryOf(response);
        return StraightLineLegs.distribute(segment, (int) Math.round(summary.distance()),
                (int) Math.round(summary.duration() / MILLIS_PER_SECOND));
    }

    /**
     * 공급자에 닿지 못한 전부를 포트 예외 하나로 모은다 — 호출부가 원인별로 분기하지 않게 하기 위함이다.
     *
     * <p>{@code private} 이 아닌 것은 Resilience4j 가 리플렉션으로 찾기 때문이고, 시그니처가 원
     * 메서드 + {@link Throwable} 인 것도 그 규약이다.
     */
    List<RoadLeg> unavailable(List<GeoPoint> segment, Duration timeout, Throwable cause) {
        throw new MapRouteUnavailableException("도로 경로 조회 실패: 지점 " + segment.size() + "개", cause,
                cause instanceof CallNotPermittedException);
    }

    /**
     * 첫 지점이 {@code start}, 마지막이 {@code goal}, 나머지가 {@code waypoints} 다 — NCP 는
     * <b>경도를 먼저</b> 적는다.
     */
    private URI drivingUri(List<GeoPoint> segment) {
        GeoPoint start = segment.getFirst();
        GeoPoint goal = segment.getLast();
        StringBuilder url = new StringBuilder(baseUrl).append(DRIVING_PATH)
                .append("?start=").append(coordinate(start))
                .append("&goal=").append(coordinate(goal));
        List<GeoPoint> middle = segment.subList(1, segment.size() - 1);
        if (!middle.isEmpty()) {
            url.append("&waypoints=")
                    .append(middle.stream().map(NaverDirectionsGateway::coordinate).collect(Collectors.joining("|")));
        }
        return URI.create(url.toString());
    }

    private static String coordinate(GeoPoint point) {
        return point.lng().toPlainString() + "," + point.lat().toPlainString();
    }

    /**
     * 경로를 찾지 못한 응답도 실패로 올린다 — 여기서 예외를 내면 위 {@code fallbackMethod} 를 거쳐
     * 직선거리 근사로 이어지고, 회차 하나가 경로 부재로 통째로 멈추지 않는다.
     */
    private static Summary summaryOf(DrivingResponse response) {
        if (response == null || response.route() == null || response.route().trafast() == null
                || response.route().trafast().isEmpty()) {
            throw new IllegalStateException("네이버 응답에 경로가 없다");
        }
        return response.route().trafast().getFirst().summary();
    }

    /** NCP 응답 최상위. */
    record DrivingResponse(RouteWrapper route) {
    }

    /** {@code option} 을 지정하지 않았을 때의 기본 탐색 결과 묶음이 {@code trafast} 다. */
    record RouteWrapper(List<Trafast> trafast) {
    }

    record Trafast(Summary summary) {
    }

    /** 구간 총합만 온다 — 경유지별 값은 NCP 가 주지 않아 {@link StraightLineLegs} 가 비율로 가른다. */
    record Summary(double distance, double duration) {
    }
}
