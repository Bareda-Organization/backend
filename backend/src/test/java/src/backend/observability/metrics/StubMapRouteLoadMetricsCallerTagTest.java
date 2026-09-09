package src.backend.observability.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import src.backend.routing.map.spec.CallerPolicy;

/**
 * F5 S2 목표 4 — {@code throttled_total} 이 {@code caller} 태그로 BATCH·ON_DEMAND 를
 * 따로 세는지 검증한다. 이 계측의 존재 이유는 격벽 상한 초과가 어느 호출자에서 더 걸리는지
 * 구별하는 것이라, 태그가 상수로 고정되면(F5 S2 목표 4 결함 심기 ②) 이 시험이 유일하게 잡는다.
 */
class StubMapRouteLoadMetricsCallerTagTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final StubMapRouteLoadMetrics metrics = new StubMapRouteLoadMetrics(registry);

    @Test
    void bothCallerCounters_startAtZero() {
        assertThat(throttled(CallerPolicy.BATCH)).isEqualTo(0.0d);
        assertThat(throttled(CallerPolicy.ON_DEMAND)).isEqualTo(0.0d);
    }

    @Test
    void recordThrottled_countsEachCallerSeparately() {
        metrics.recordThrottled(CallerPolicy.BATCH);
        metrics.recordThrottled(CallerPolicy.BATCH);
        metrics.recordThrottled(CallerPolicy.ON_DEMAND);

        assertThat(throttled(CallerPolicy.BATCH)).isEqualTo(2.0d);
        assertThat(throttled(CallerPolicy.ON_DEMAND)).isEqualTo(1.0d);
    }

    private double throttled(CallerPolicy caller) {
        return registry.get("schoolbus.routing.stub.load.throttled")
                .tag("caller", caller.name().toLowerCase(Locale.ROOT))
                .counter().count();
    }
}
