package src.backend.routing.map.impl;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import src.backend.observability.metrics.StubMapRouteLoadMetrics;
import src.backend.routing.map.spec.CallerPolicy;
import src.backend.routing.map.spec.MapRouteUnavailableException;
import src.backend.routing.map.spec.RoadRoute;
import src.backend.routing.map.spec.RoadRouteRequest;

/** {@link StubMapRouteClient} 의 부하 시험용 지연·실패·동시성 상한 주입 — 실 시스템의 격벽·타임아웃·서킷 개방을 흉내 낸다. */
final class StubLoadInjection {

    private final long minDelayMs;

    private final long maxDelayMs;

    private final double failureRate;

    private final int maxConcurrent;

    private final StubMapRouteLoadMetrics loadMetrics;

    private final AtomicInteger inFlight = new AtomicInteger(0);

    StubLoadInjection(long minDelayMs, long maxDelayMs, double failureRate, int maxConcurrent,
            StubMapRouteLoadMetrics loadMetrics) {
        this.minDelayMs = minDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.failureRate = failureRate;
        this.maxConcurrent = maxConcurrent;
        this.loadMetrics = loadMetrics;
    }

    /** 위 값 중 하나라도 비활성 기본값(지연 0 · 상한 무제한 · 실패율 0)이 아니면 참이다. */
    boolean enabled() {
        return maxDelayMs > 0 || failureRate > 0 || maxConcurrent < Integer.MAX_VALUE;
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
    RoadRoute apply(RoadRouteRequest request) {
        int current = inFlight.incrementAndGet();
        try {
            if (current > maxConcurrent) {
                loadMetrics.recordThrottled(request.caller());
                return StraightLineLegs.approximate(request.points());
            }
            RoadRoute timedOut = applyDelay(request);
            return timedOut != null ? timedOut : applyFailure(request);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    /** 지연이 {@code request.timeout()} 이상이면 시간 초과를 모사한 폴백을, 아니면 지연만큼 자고 {@code null} 을 낸다. */
    private RoadRoute applyDelay(RoadRouteRequest request) {
        if (maxDelayMs <= 0) {
            return null;
        }
        long delay = ThreadLocalRandom.current().nextLong(minDelayMs, maxDelayMs + 1);
        if (delay >= request.timeout().toMillis()) {
            loadMetrics.recordTimeout();
            return StraightLineLegs.approximate(request.points());
        }
        sleepUninterruptibly(delay);
        return null;
    }

    /** 확률로 뽑힌 실패를 서킷 개방으로 모사한다 — {@code ON_DEMAND} 는 예외로 올리고 그 밖은 폴백으로 흡수한다. */
    private RoadRoute applyFailure(RoadRouteRequest request) {
        if (failureRate > 0 && ThreadLocalRandom.current().nextDouble() < failureRate) {
            loadMetrics.recordFailureInjected();
            if (request.caller() == CallerPolicy.ON_DEMAND) {
                throw new MapRouteUnavailableException("부하 시험 주입 실패(서킷 개방 모사)", null, true);
            }
            return StraightLineLegs.approximate(request.points());
        }
        return null;
    }

    private static void sleepUninterruptibly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
