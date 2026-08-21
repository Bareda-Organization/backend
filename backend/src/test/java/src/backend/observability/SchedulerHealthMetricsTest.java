package src.backend.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import src.backend.observability.metrics.SchedulerHealthMetrics;

/** 시간 흐름은 주입한 시계로 조작해 대기 없이 검증한다. */
class SchedulerHealthMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final SchedulerHealthMetrics metrics = new SchedulerHealthMetrics(registry, now::get);

    @Test
    void recordSuccess_registersGaugeStartingAtZero() {
        metrics.recordSuccess("sos-escalation");
        assertThat(gauge("sos-escalation")).isEqualTo(0.0d);
    }

    /** 이 테스트가 계측의 존재 이유다 — 성공이 멈추면 경과가 늘어야 정지를 관측할 수 있다. */
    @Test
    void gauge_growsWhileSchedulerStopsSucceeding() {
        metrics.recordSuccess("sos-escalation");
        now.addAndGet(90_000L);
        assertThat(gauge("sos-escalation")).isEqualTo(90.0d);
    }

    @Test
    void recordSuccess_resetsAge() {
        metrics.recordSuccess("sos-escalation");
        now.addAndGet(90_000L);
        metrics.recordSuccess("sos-escalation");
        assertThat(gauge("sos-escalation")).isEqualTo(0.0d);
    }

    /** 실패는 성공 시각을 갱신하지 않는다 — 실패만 반복하면 경과가 계속 늘어야 한다. */
    @Test
    void recordFailure_countsAndDoesNotResetAge() {
        metrics.recordSuccess("connection-loss");
        now.addAndGet(30_000L);
        metrics.recordFailure("connection-loss");

        assertThat(gauge("connection-loss")).isEqualTo(30.0d);
        assertThat(registry.counter("schoolbus.scheduler.failures", "scheduler", "connection-loss").count())
                .isEqualTo(1.0d);
    }

    @Test
    void schedulers_areTrackedIndependently() {
        metrics.recordSuccess("sos-escalation");
        now.addAndGet(60_000L);
        metrics.recordSuccess("connection-loss");

        assertThat(gauge("sos-escalation")).isEqualTo(60.0d);
        assertThat(gauge("connection-loss")).isEqualTo(0.0d);
    }

    private double gauge(String scheduler) {
        return registry.get("schoolbus.scheduler.last.success.age")
                .tag("scheduler", scheduler).gauge().value();
    }
}
