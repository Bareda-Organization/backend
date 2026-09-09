package src.backend.observability.metrics;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/** ②구간 자동 거절 건수를 센다({@code TECH_DECISIONS §13.1}). */
@Component
public class ChangeRequestAutoRejectionMetrics {

    private static final String METRIC = "schoolbus.change_request.auto_rejected";

    private final Counter counter;

    // 생성자에서 즉시 등록한다 — 자동 거절이 한 번도 없는 기동 직후에도 이름이 노출돼야 한다.
    public ChangeRequestAutoRejectionMetrics(MeterRegistry registry) {
        this.counter = Counter.builder(METRIC)
                .description("마감을 넘겨 자동 거절된 ②구간 변경 요청 건수")
                .register(registry);
    }

    public void recordRejected() {
        counter.increment();
    }
}
