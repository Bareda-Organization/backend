package src.backend.routing.map.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;

import src.backend.global.error.ErrorCode;
import src.backend.routing.domain.GeoPoint;
import src.backend.routing.map.spec.CallerPolicy;
import src.backend.routing.map.spec.MapRouteClient;
import src.backend.routing.map.spec.MapRouteUnavailableException;
import src.backend.routing.map.spec.RoadRoute;
import src.backend.routing.map.spec.RoadRouteRequest;

/**
 * 도로 경로 어댑터의 보호·분할·폴백을 <b>공급자에 도달한 요청</b>으로 고정한다(§7 규칙 11).
 *
 * <p>응답만 보면 아무것도 가릴 수 없다 — 재시도가 걸린 경우와 걸리지 않은 경우, 구간을 나눠 부른
 * 경우와 상한까지만 보내고 나머지를 버린 경우가 <b>전부 같은 모양의 결과</b>를 낸다. Phase 5 가
 * 실제로 그 상태였다({@code max-attempts: 3} 인데 실 호출은 1회). 그래서 이 클래스는 공급자 자리에
 * <b>로컬 HTTP 서버</b>를 세우고 도달한 요청 수와 <b>요청마다 실린 지점 수</b>를 함께 센다.
 *
 * <p>실 네이버를 부르지 않는 이유는 판정이 네트워크·요금·NCP 계정 상태에 매달리면 그 결과가 코드에
 * 대해 아무것도 말하지 않기 때문이다(Ruling 157). 실 응답 형태는
 * {@code NaverDirectionsClientLiveTest} 가 자격증명이 있을 때만 따로 본다.
 */
@SpringBootTest(properties = {
        // 스텁은 재시도·서킷을 검사할 수 없다 — 이 검사의 대상이 어댑터의 애너테이션 자체다.
        // 테스트 전체 묶음은 build.gradle 이 stub 으로 고정하므로 여기서만 되돌린다.
        "app.routing.map.provider=naver",
        "app.routing.map.naver.key-id=test-key-id",
        "app.routing.map.naver.key=test-key",
        // 이 컨텍스트는 DB 를 쓰지 않는다. 컨텍스트마다 Hikari 가 기본 상한만큼 커넥션을 붙든 채
        // JVM 이 끝날 때까지 남아, 상한을 낮추지 않으면 이 클래스를 더한 것만으로 다른 컨텍스트가
        // "sorry, too many clients already" 로 못 뜬다(NaverGeocodingResilienceTest 와 같은 이유).
        "spring.datasource.hikari.maximum-pool-size=2"
})
class NaverDirectionsResilienceTest {

    /** 공급자에 도달한 요청 수 — 재시도·구간 분할이 실제로 걸렸는지를 이 값으로 가린다. */
    private static final AtomicInteger PROVIDER_HITS = new AtomicInteger();

    /** 요청마다 실린 지점 수(start + waypoints + goal) — 상한 준수와 누락을 함께 가린다. */
    private static final List<Integer> POINTS_PER_REQUEST = Collections.synchronizedList(new ArrayList<>());

    /** 다음 응답을 몇 밀리초 늦출지 — 타임아웃을 <b>주입값대로</b> 유발하기 위한 손잡이다. */
    private static final AtomicLong RESPONSE_DELAY_MILLIS = new AtomicLong();

    /** 참이면 500 만 돌려준다 — 재시도·서킷 개방을 만드는 입력이다. */
    private static final AtomicInteger FAIL_MODE = new AtomicInteger();

    /**
     * 공급자 대역.
     *
     * <p>정적 초기화로 띄우는 것은 {@link DynamicPropertySource} 가 컨텍스트를 띄우기 <b>전에</b>
     * 포트를 알아야 하기 때문이다. 포트를 0으로 열어 OS 가 고르게 한다 — 고정 포트를 쓰면 같은
     * 저장소에서 에이전트가 둘 돌 때 한쪽이 점유해 다른 쪽이 기동 실패한다.
     */
    private static final HttpServer PROVIDER = startProvider();

    @Autowired
    private MapRouteClient mapRouteClient;

    @Autowired
    private RetryRegistry retryRegistry;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    /** 상한을 테스트에 옮겨 적지 않는다 — 옮겨 적으면 yml 값이 바뀔 때 두 값이 조용히 갈린다. */
    @Value("${app.routing.map.max-waypoints}")
    private int maxWaypoints;

    @DynamicPropertySource
    static void 공급자_자리에_로컬_서버를_세운다(DynamicPropertyRegistry registry) {
        registry.add("app.routing.map.naver.base-url",
                () -> "http://localhost:" + PROVIDER.getAddress().getPort());
    }

    @AfterAll
    static void 공급자_대역을_내린다() {
        PROVIDER.stop(0);
    }

    /**
     * 서킷은 호출 이력을 누적하므로 테스트마다 되돌린다 — 되돌리지 않으면 앞 테스트가 남긴 실패로
     * 서킷이 열려 뒤 테스트의 판정이 <b>실행 순서에 따라</b> 갈린다.
     */
    @BeforeEach
    void 서킷과_계수기를_되돌린다() {
        circuitBreakerRegistry.circuitBreaker(NaverDirectionsGateway.RESILIENCE_INSTANCE).reset();
        PROVIDER_HITS.set(0);
        POINTS_PER_REQUEST.clear();
        RESPONSE_DELAY_MILLIS.set(0);
        FAIL_MODE.set(0);
    }

    /**
     * 일시 실패는 {@code max-attempts} 만큼 다시 부른다.
     *
     * <p>도달한 호출 수로 세는 이유는, 결과만 보면 재시도가 걸린 경우와 걸리지 않은 경우가 <b>똑같이
     * {@code fallbackUsed=true}</b> 라 구별할 수단이 부재하기 때문이다. {@code fallbackMethod} 를
     * {@code @CircuitBreaker} 쪽으로 옮기면 이 단언이 1을 보고 실패한다.
     */
    @Test
    void 일시_실패는_설정한_횟수만큼_다시_부른다() {
        assertThat(maxAttempts())
                .as("max-attempts 가 1이면 아래 단언이 '재시도가 걸린 상태'와 '걸리지 않은 상태'를 구별하지 못한다")
                .isGreaterThan(1);
        FAIL_MODE.set(1);

        RoadRoute route = mapRouteClient.route(요청(지점_두개(), Duration.ofSeconds(2), CallerPolicy.BATCH));

        assertThat(route.fallbackUsed()).isTrue();
        assertThat(PROVIDER_HITS.get())
                .as("공급자에 도달한 호출 수 — 1이면 재시도가 걸리지 않은 것이다(fallbackMethod 가 @Retry 안쪽에 있는 형태)")
                .isEqualTo(maxAttempts());
    }

    /**
     * 목표 3 — 타임아웃이 나면 직선거리 근사로 결과가 나오고 <b>구간 수가 그대로 복원</b>된다.
     *
     * <p>구간 수를 함께 보는 이유는 폴백이 "빈 결과" 로 끝나도 {@code fallbackUsed} 만으로는 그것을
     * 알 수 없기 때문이다. 거리까지 보는 것은 폴백이 0을 채워 넣어도 형태는 맞기 때문이다.
     */
    @Test
    void 타임아웃이_나면_폴백으로_결과가_나오고_구간_수가_복원된다() {
        RESPONSE_DELAY_MILLIS.set(1500);
        List<GeoPoint> points = 지점_여러개(4);

        RoadRoute route = mapRouteClient.route(요청(points, Duration.ofMillis(200), CallerPolicy.BATCH));

        assertThat(route.fallbackUsed())
                .as("폴백 사실이 실리지 않으면 근사값이 실측값과 구별되지 않는다")
                .isTrue();
        assertThat(route.legs())
                .as("legs.size() == points.size() - 1 이 폴백에서도 성립해야 호출부가 형태로 분기하지 않는다")
                .hasSize(points.size() - 1);
        assertThat(route.legs()).allSatisfy(leg -> {
            assertThat(leg.distanceMeters()).isPositive();
            assertThat(leg.durationSeconds()).isPositive();
        });
        assertThat(route.legs().getFirst().distanceMeters())
                .as("폴백 거리는 두 지점의 직선거리다")
                .isEqualTo((int) Math.round(points.get(0).distanceMetersTo(points.get(1))));
    }

    /**
     * 주입한 타임아웃이 <b>실제로 기다리는 시간</b>을 정한다 — 같은 서버에 대해 결과가 갈린다.
     *
     * <p>공유 {@code webClient} 빈(응답 타임아웃 5초 고정)을 그대로 쓰면 배치가 주입한 긴 타임아웃이
     * 조용히 5초로 잘린다. 이 시험이 없으면 {@code ARCHITECTURE §8.3} 의 "배치는 길게 · 온디맨드는
     * 짧게" 가 설정에만 남고 동작은 하나로 뭉개진다.
     */
    @Test
    void 주입한_타임아웃이_1회_요청의_상한을_정한다() {
        RESPONSE_DELAY_MILLIS.set(700);

        RoadRoute 짧은_상한 = mapRouteClient.route(요청(지점_두개(), Duration.ofMillis(200), CallerPolicy.ON_DEMAND));
        RoadRoute 긴_상한 = mapRouteClient.route(요청(지점_두개(), Duration.ofSeconds(3), CallerPolicy.BATCH));

        assertThat(짧은_상한.fallbackUsed())
                .as("200ms 상한인데 700ms 응답을 기다렸다 — 주입값이 무시되고 있다")
                .isTrue();
        assertThat(긴_상한.fallbackUsed())
                .as("3초 상한인데 700ms 응답을 못 받았다 — 주입값이 더 짧은 값으로 잘리고 있다")
                .isFalse();
    }

    /**
     * 목표 4 — 경유지 상한을 넘으면 <b>나눠 부르고</b>, 이어 붙인 구간 수가 복원된다.
     *
     * <p>세 가지를 함께 본다. ①호출이 2회 이상이다 ②요청마다 실린 지점이 상한을 넘지 않는다
     * ③요청별 구간 수의 합이 입력 구간 수와 <b>정확히</b> 같다. ③이 없으면 상한까지만 보내고 나머지를
     * 버리는 구현이 그대로 통과한다 — 결과 길이는 폴백이 채워 주고 총 거리만 조금 줄어들 뿐이라
     * 눈으로 드러나지 않는다.
     */
    @Test
    void 경유지_상한을_넘으면_구간을_나눠_부르고_구간_수가_복원된다() {
        List<GeoPoint> points = 지점_여러개(maxWaypoints + 3);

        RoadRoute route = mapRouteClient.route(요청(points, Duration.ofSeconds(3), CallerPolicy.BATCH));

        assertThat(route.fallbackUsed()).isFalse();
        assertThat(route.legs()).hasSize(points.size() - 1);
        assertThat(PROVIDER_HITS.get())
                .as("상한을 넘겼는데 한 번만 불렀다 — 나누지 않고 잘라 보냈다는 뜻이다")
                .isGreaterThanOrEqualTo(2);
        assertThat(POINTS_PER_REQUEST)
                .as("한 요청에 상한을 넘는 지점이 실렸다 — 공급자가 거부할 요청이다")
                .allMatch(count -> count <= maxWaypoints);
        assertThat(POINTS_PER_REQUEST.stream().mapToInt(count -> count - 1).sum())
                .as("요청별 구간 수의 합이 입력 구간 수와 다르다 — 경계 지점이 중복됐거나 버려졌다")
                .isEqualTo(points.size() - 1);
    }

    /**
     * 목표 5 — 서킷이 열리면 온디맨드는 즉시 오류, 배치는 폴백이다.
     *
     * <p>둘을 안 가르면 관리자가 근사 경로를 실제 경로로 믿고 승인한다. <b>응답 시간이 주입한
     * 타임아웃보다 짧다</b>는 것을 함께 보는 이유는, 서킷이 열려 있어도 공급자를 두드리는 구현이면
     * 결과는 같고 대기 시간만 늘기 때문이다 — 그 상태에서 서킷은 아무것도 보호하지 않는다.
     */
    @Test
    void 서킷이_열리면_온디맨드는_즉시_오류이고_배치는_폴백이다() {
        Duration 상한 = Duration.ofSeconds(2);
        서킷을_연속_실패로_연다(상한);
        PROVIDER_HITS.set(0);

        long 온디맨드_시작 = System.nanoTime();
        assertThatThrownBy(() -> mapRouteClient.route(요청(지점_두개(), 상한, CallerPolicy.ON_DEMAND)))
                .as("서킷이 열렸는데 온디맨드가 근사값을 돌려줬다 — 관리자가 그것을 실제 경로로 승인한다")
                .isInstanceOf(MapRouteUnavailableException.class);
        long 온디맨드_경과 = Duration.ofNanos(System.nanoTime() - 온디맨드_시작).toMillis();

        long 배치_시작 = System.nanoTime();
        RoadRoute 배치 = mapRouteClient.route(요청(지점_두개(), 상한, CallerPolicy.BATCH));
        long 배치_경과 = Duration.ofNanos(System.nanoTime() - 배치_시작).toMillis();

        assertThat(배치.fallbackUsed())
                .as("배치는 사용자가 대기 중이 아니므로 서킷이 열려도 근사값으로 진행한다")
                .isTrue();
        assertThat(온디맨드_경과)
                .as("서킷이 열렸는데 타임아웃만큼 기다렸다 — 즉시 반환이 아니다")
                .isLessThan(상한.toMillis());
        assertThat(배치_경과).isLessThan(상한.toMillis());
        assertThat(PROVIDER_HITS.get())
                .as("서킷이 열렸는데 공급자에 요청이 나갔다 — 서킷이 아무것도 막지 않는 상태다")
                .isZero();
    }

    /**
     * {@code MAP_ROUTE_UNAVAILABLE} 이 <b>503</b> 이라는 판정이 여기서만 고정된다(API_SPEC §8.5).
     *
     * <p>사양에서 손으로 옮긴 리터럴을 쓴다 — 상수에서 유도하면 상수가 잘못 채워져도 대조 대상이
     * 같은 값을 베껴 항상 일치한다({@code ErrorCodeCatalogTest} 와 같은 이유).
     */
    @Test
    void MAP_ROUTE_UNAVAILABLE_은_503_이다() {
        assertThat(ErrorCode.MAP_ROUTE_UNAVAILABLE.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    /**
     * 연속 실패로 서킷을 연다 — {@code transitionToOpenState()} 로 손으로 열지 않는 이유는 실패가
     * 쌓여 열리는 경로 자체가 검사 대상이기 때문이다.
     */
    private void 서킷을_연속_실패로_연다(Duration 상한) {
        FAIL_MODE.set(1);
        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker(NaverDirectionsGateway.RESILIENCE_INSTANCE);
        for (int i = 0; i < 5 && breaker.getState() != CircuitBreaker.State.OPEN; i++) {
            mapRouteClient.route(요청(지점_두개(), 상한, CallerPolicy.BATCH));
        }
        assertThat(breaker.getState())
                .as("연속 실패가 쌓였는데 서킷이 열리지 않았다 — minimum-number-of-calls·failure-rate-threshold 확인")
                .isEqualTo(CircuitBreaker.State.OPEN);
    }

    private static RoadRouteRequest 요청(List<GeoPoint> points, Duration timeout, CallerPolicy caller) {
        return new RoadRouteRequest(points, timeout, caller);
    }

    private static List<GeoPoint> 지점_두개() {
        return 지점_여러개(2);
    }

    /** 위도만 일정 간격으로 늘린 지점열 — 간격이 있어야 직선거리가 0이 아니어서 배분이 검사된다. */
    private static List<GeoPoint> 지점_여러개(int count) {
        List<GeoPoint> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            points.add(new GeoPoint(new BigDecimal("37.500000").add(new BigDecimal("0.010000").multiply(BigDecimal.valueOf(i))),
                    new BigDecimal("127.000000")));
        }
        return points;
    }

    private int maxAttempts() {
        return retryRegistry.retry(NaverDirectionsGateway.RESILIENCE_INSTANCE).getRetryConfig().getMaxAttempts();
    }

    private static HttpServer startProvider() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            // 지연 응답을 겹쳐 받으려면 처리 스레드가 여럿이어야 한다 — 기본 실행기는 요청을 줄 세운다.
            server.setExecutor(Executors.newFixedThreadPool(4));
            server.createContext("/", NaverDirectionsResilienceTest::응답한다);
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void 응답한다(HttpExchange exchange) throws IOException {
        PROVIDER_HITS.incrementAndGet();
        POINTS_PER_REQUEST.add(지점_수(exchange.getRequestURI()));
        지연한다();
        // Content-Type 을 붙이지 않으면 WebClient 가 본문을 읽지 못해 정상 응답까지 폴백으로 떨어진다 —
        // 그러면 이 클래스의 "정상 경로" 단언이 전부 폴백을 보고 실패한다(실제로 그렇게 나왔다).
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        byte[] body = (FAIL_MODE.get() == 1 ? "{\"error\":\"boom\"}" : 정상_응답()).getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(FAIL_MODE.get() == 1 ? 500 : 200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    /** {@code start} 1개 + {@code goal} 1개 + {@code waypoints} 를 {@code |} 로 가른 수. */
    private static int 지점_수(URI uri) {
        String query = uri.getQuery() == null ? "" : uri.getQuery();
        int waypoints = 0;
        for (String each : query.split("&")) {
            if (each.startsWith("waypoints=") && each.length() > "waypoints=".length()) {
                waypoints = each.substring("waypoints=".length()).split("\\|").length;
            }
        }
        return waypoints + 2;
    }

    private static void 지연한다() {
        long delay = RESPONSE_DELAY_MILLIS.get();
        if (delay <= 0) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** NCP Direction 응답의 최소 형태 — 거리·시간 총합만 읽으므로 그 둘만 담는다. */
    private static String 정상_응답() {
        return "{\"route\":{\"trafast\":[{\"summary\":{\"distance\":12000,\"duration\":900000}}]}}";
    }
}
