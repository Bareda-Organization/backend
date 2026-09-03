package src.backend.observability.metrics;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/** 미확정 회차 수 게이지({@code TECH_DECISIONS §13.1·§13.4}). */
@Component
public class RunUnconfirmedMetrics {

    private static final String METRIC = "schoolbus.run.unconfirmed";

    private final AtomicLong value = new AtomicLong(0);

    // 생성자에서 즉시 등록한다 — 갱신 스케줄러가 한 번도 안 돈 기동 직후에도 이름이 0으로 노출돼야 한다.
    public RunUnconfirmedMetrics(MeterRegistry registry) {
        Gauge.builder(METRIC, value, AtomicLong::get)
                .description("판정 시각을 5분 넘긴(경고 여유) idle 회차 수")
                .register(registry);
    }

    public void update(long count) {
        value.set(count);
    }
}
