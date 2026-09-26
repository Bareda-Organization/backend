package src.backend.observability.metrics;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/** Redis 장애로 최신 좌표를 {@code run_position} 최신 행으로 대체 조회한 횟수(BR-167, TECH_DECISIONS §14.2). */
@Component
public class RunPositionFallbackMetrics {

    private static final String METRIC = "schoolbus.position.fallback";

    private final Counter counter;

    // 생성자에서 즉시 등록한다 — 대체가 한 번도 없는 기동 직후에도 이름이 0으로 노출돼야 한다.
    public RunPositionFallbackMetrics(MeterRegistry registry) {
        this.counter = Counter.builder(METRIC)
                .description("Redis 장애로 최신 좌표를 DB 이력 최신 행으로 대체 조회한 횟수")
                .register(registry);
    }

    /** 대체 조회 1회를 센다(회차 수와 무관 — 조회문 한 번이 1). */
    public void recordFallback() {
        counter.increment();
    }
}
