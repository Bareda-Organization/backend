package src.backend.routing.map.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import src.backend.observability.metrics.StubMapRouteLoadMetrics;
import src.backend.routing.domain.GeoPoint;
import src.backend.routing.map.spec.CallerPolicy;
import src.backend.routing.map.spec.MapRouteClient;
import src.backend.routing.map.spec.MapRouteUnavailableException;
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
 *
 * <p>{@code request.forceFallback()} 이 참이면 마커 좌표 유무와 무관하게 폴백으로 답한다
 * (API_SPEC §6.14, 관리자 강제 확정 콘솔 개입).
 *
 * <h2>부하 시험용 프로파일 조건부 주입(F3 L, {@code IMPLEMENTATION_PLAN §5.5})</h2>
 *
 * <p>실 지도 API(Naver)는 {@code resilience4j.*}(격벽·서킷·재시도)를 {@code NaverDirectionsGateway}
 * 한 곳에서만 걸고, 그 상위 {@code NaverDirectionsClient#route} 가 실패를 폴백·오류로 가른다 — 이
 * 스텁은 그 두 계층을 흉내 낼 대상이 없어 이 클래스 안에 <b>독립적으로</b> 재현한다:
 *
 * <ul>
 *   <li><b>{@code max-concurrent}</b> — 동시 in-flight 호출이 상한을 넘으면 격벽 거부를 모사한다.
 *       실 격벽과 같은 근거로 {@code ON_DEMAND} 라도 오류가 아니라 <b>항상 폴백</b>이다
 *       ({@code NaverDirectionsGateway#legsOf} javadoc — "서킷 개방과 달리 오류가 아니다")</li>
 *   <li><b>{@code min-delay-ms}·{@code max-delay-ms}</b> — 균등분포로 지연을 모사한다. 지연이
 *       {@code request.timeout()} 이상이면 시간 초과를 모사하며, 이것도 {@code CallNotPermittedException}
 *       이 아니라 <b>항상 폴백</b>이다(실 게이트웨이의 {@code unavailable()} 이 타임아웃을
 *       {@code circuitOpen=false} 로 감싸는 것과 같은 근거)</li>
 *   <li><b>{@code failure-rate}</b> — 무작위 실패를 서킷 개방으로 모사한다. 유일하게 {@code ON_DEMAND}
 *       호출자에게 {@link MapRouteUnavailableException}(circuitOpen=true)을 그대로 던진다
 *       ({@code NaverDirectionsClient#route} 의 "호출부까지 올라오는 것은 ON_DEMAND × 서킷 개방
 *       하나뿐" 과 같은 분기) — {@code BATCH} 는 폴백으로 흡수한다</li>
 * </ul>
 *
 * <p>실 지도 API 의 지연·실패 분포는 측정 불가(API 키 미보유, F3 L 판정 고정) — 위 세 값의
 * 기본값은 전부 <b>비활성</b>(지연 0 · 상한 무제한 · 실패율 0)이라 기본·시험 프로파일(테스트
 * 전체 묶음이 이 구현으로 돈다, {@code build.gradle} 의 {@code app.routing.map.provider=stub})의
 * 동작은 이 주입이 생기기 전과 <b>바이트 단위로 동일</b>하다 — {@link #StubMapRouteClient()} 는
 * 그 비활성 상태를 그대로 고정한 편의 생성자다.
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

    private final long minDelayMs;

    private final long maxDelayMs;

    private final double failureRate;

    private final int maxConcurrent;

    private final StubMapRouteLoadMetrics loadMetrics;

    /** 위 4개 값이 전부 비활성 기본값이면 {@code route()} 의 주입 분기 자체를 타지 않는다. */
    private final boolean loadInjectionEnabled;

    private final AtomicInteger inFlight = new AtomicInteger(0);

    /** 부하 주입이 없는 기존 호출(시험 전체 묶음 포함)을 위한 편의 생성자 — 전부 비활성. */
    public StubMapRouteClient() {
        this(0L, 0L, 0d, Integer.MAX_VALUE, null);
    }

    @Autowired
    public StubMapRouteClient(
            @Value("${app.routing.map.stub.load.min-delay-ms:0}") long minDelayMs,
            @Value("${app.routing.map.stub.load.max-delay-ms:0}") long maxDelayMs,
            @Value("${app.routing.map.stub.load.failure-rate:0}") double failureRate,
            @Value("${app.routing.map.stub.load.max-concurrent:2147483647}") int maxConcurrent,
            StubMapRouteLoadMetrics loadMetrics) {
        this.minDelayMs = minDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.failureRate = failureRate;
        this.maxConcurrent = maxConcurrent;
        this.loadMetrics = loadMetrics;
        this.loadInjectionEnabled = maxDelayMs > 0 || failureRate > 0 || maxConcurrent < Integer.MAX_VALUE;
    }

    @Override
    public RoadRoute route(RoadRouteRequest request) {
        if (request.forceFallback() || hasUnavailableMarker(request.points())) {
            return StraightLineLegs.approximate(request.points());
        }
        if (loadInjectionEnabled) {
            RoadRoute injected = applyLoadInjection(request);
            if (injected != null) {
                return injected;
            }
        }
        List<GeoPoint> points = request.points();
        List<RoadLeg> legs = new ArrayList<>(points.size() - 1);
        for (int i = 0; i < points.size() - 1; i++) {
            int meters = (int) Math.round(points.get(i).distanceMetersTo(points.get(i + 1)) * ROAD_DETOUR_FACTOR);
            legs.add(new RoadLeg(meters, secondsFor(meters)));
        }
        return new RoadRoute(legs, false);
    }

    /**
     * 부하 주입 3종을 이 순서로 적용한다 — 격벽(동시성) → 지연(타임아웃) → 실패율. 실 시스템의
     * 계층 순서(격벽이 가장 바깥, 그 다음 응답 대기, 실패는 응답을 받은 뒤에야 가른다)와 같다.
     *
     * @return 폴백으로 흡수됐으면 그 {@link RoadRoute}, 아무 주입도 걸리지 않았으면 {@code null}
     *         (호출부가 정상 경로를 계속 계산한다)
     * @throws MapRouteUnavailableException {@code failure-rate} 로 뽑힌 실패가 {@code ON_DEMAND}
     *                                       호출자에게 서킷 개방으로 올라갈 때
     */
    private RoadRoute applyLoadInjection(RoadRouteRequest request) {
        int current = inFlight.incrementAndGet();
        try {
            if (current > maxConcurrent) {
                loadMetrics.recordThrottled();
                return StraightLineLegs.approximate(request.points());
            }
            if (maxDelayMs > 0) {
                long delay = ThreadLocalRandom.current().nextLong(minDelayMs, maxDelayMs + 1);
                if (delay >= request.timeout().toMillis()) {
                    loadMetrics.recordTimeout();
                    return StraightLineLegs.approximate(request.points());
                }
                sleepUninterruptibly(delay);
            }
            if (failureRate > 0 && ThreadLocalRandom.current().nextDouble() < failureRate) {
                loadMetrics.recordFailureInjected();
                if (request.caller() == CallerPolicy.ON_DEMAND) {
                    throw new MapRouteUnavailableException("부하 시험 주입 실패(서킷 개방 모사)", null, true);
                }
                return StraightLineLegs.approximate(request.points());
            }
            return null;
        } finally {
            inFlight.decrementAndGet();
        }
    }

    private static void sleepUninterruptibly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean hasUnavailableMarker(List<GeoPoint> points) {
        return points.stream().anyMatch(point -> point.lat().compareTo(UNAVAILABLE_MARKER_LAT) == 0);
    }

    private static int secondsFor(int meters) {
        return (int) Math.round(meters * SECONDS_PER_HOUR / (STUB_SPEED_KMH * METERS_PER_KILOMETER));
    }
}
