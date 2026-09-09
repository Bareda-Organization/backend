package src.backend.observability.metrics;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/** 미승차 에스컬레이션 건수를 센다({@code TECH_DECISIONS §13.1}). */
@Component
public class NoShowEscalationMetrics {

    private static final String METRIC = "schoolbus.no_show.escalated";

    private final Counter counter;

    // 생성자에서 즉시 등록한다 — 에스컬레이션이 한 번도 없는 기동 직후에도 이름이 노출돼야 한다.
    public NoShowEscalationMetrics(MeterRegistry registry) {
        this.counter = Counter.builder(METRIC)
                .description("대기 만료 + 무응답으로 에스컬레이션된 미승차 케이스 건수")
                .register(registry);
    }

    public void recordEscalated() {
        counter.increment();
    }
}
