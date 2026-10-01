package src.backend.observability.metrics;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/** 어제보다 이른 운행일에 끝나지 않은 이동 중 회차 수 게이지 — {@code StaleMovingRun} 경보의 재료(R46-KFIXBE K-1, Ruling 702). */
@Component
public class StaleMovingRunMetrics {

    private static final String METRIC = "schoolbus.run.moving.stale";

    private final AtomicLong value = new AtomicLong(0);

    // 생성자에서 즉시 등록한다 — 갱신 스케줄러가 한 번도 안 돈 기동 직후에도 이름이 0으로 노출돼야 한다.
    public StaleMovingRunMetrics(MeterRegistry registry) {
        Gauge.builder(METRIC, value, AtomicLong::get)
                .description("운행일이 어제보다 이른데 끝나지 않은 이동 중 회차 수")
                .register(registry);
    }

    /** 최근 집계값으로 게이지를 바꾼다. */
    public void update(long count) {
        value.set(count);
    }
}
