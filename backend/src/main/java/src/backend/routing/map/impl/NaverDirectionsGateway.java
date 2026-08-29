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
import org.springframework.web.util.UriComponentsBuilder;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
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

    /** NCP 가 경유지를 가르는 문자 — URI 에 그대로 실을 수 없어 인코딩을 거친다. */
    private static final String WAYPOINT_SEPARATOR = "|";

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
     * <p><b>{@code @Bulkhead} 는 동시 호출 수를 막는다</b>({@code TECH_DECISIONS §8} ·
     * {@code ARCHITECTURE §9.4}) — 상한을 넘은 호출은 <b>기다리지 않고 거부</b>된다
     * ({@code max-wait-duration: 0}). 대기를 두면 호출자가 주입한 타임아웃 예산 밖의 시간이 앞에
     * 붙어, 관리자가 승인 화면에서 얼마를 기다릴지를 yml 값이 정하게 된다. 거부는 단발 실패와 같은
     * 경로로 흡수되어 직선거리 근사가 된다 — 서킷 개방과 달리 {@code ON_DEMAND} 라도 오류가 아니다.
     *
     * <p><b>{@code fallbackMethod} 가 {@code @Retry} 쪽에 있어야 한다.</b> 두 애스펙트의 순서는
     * Retry 가 바깥 · CircuitBreaker 가 안쪽으로 고정돼 있다({@code order} 2147483642 · 2147483643).
     * 안쪽에 fallback 을 걸면 {@link io.github.resilience4j.circuitbreaker.CallNotPermittedException}
     * 이 바깥 Retry 에 닿기 전에 {@link MapRouteUnavailableException} 으로 바뀌어,
     * {@code ignore-exceptions} 가 그것을 알아보지 못한다 — <b>서킷이 열려 있는데도 재시도가 돌아</b>
     * 열린 서킷을 세 번 두드리고 {@code wait-duration} 만큼 응답만 늦어진다. 공급자에는 닿지 않고
     * 응답 코드도 그대로라 어느 기능 시험에도 드러나지 않는다.
     *
     * <p>⚠ <b>도달한 호출 수만으로는 이 배치를 가릴 수 없다</b>(2026-08-29 실측) — 폴백이 값을
     * 돌려주지 않고 예외를 던지므로 두 배치 모두 {@code max-attempts} 만큼 공급자를 부른다. 순서를
     * 고정하는 것은 {@code NaverDirectionsResilienceTest} 의 <b>재시도 없이 실패한 호출 수</b>
     * 단언이고, 호출 수 단언이 잡는 것은 재시도가 아예 안 걸린 상태다.
     *
     * @throws MapRouteUnavailableException 공급자에 닿지 못한 전부 — 타임아웃 · 5xx · 서킷 개방
     */
    @Bulkhead(name = RESILIENCE_INSTANCE)
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
                .path(DRIVING_PATH)
                .queryParam("start", coordinate(segment.getFirst()))
                .queryParam("goal", coordinate(segment.getLast()));
        List<GeoPoint> middle = segment.subList(1, segment.size() - 1);
        if (!middle.isEmpty()) {
            url.queryParam("waypoints", middle.stream()
                    .map(NaverDirectionsGateway::coordinate)
                    .collect(Collectors.joining(WAYPOINT_SEPARATOR)));
        }
        return url.build().encode().toUri();
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
