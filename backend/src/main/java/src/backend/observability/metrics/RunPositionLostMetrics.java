package src.backend.observability.metrics;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/** 위치가 2분 넘게 안 들어온 운행 중 회차 수 게이지 — {@code TECH_DECISIONS §13.4} 경고 알럿의 재료(BR-064). */
@Component
public class RunPositionLostMetrics {

    private static final String METRIC = "schoolbus.run.position.lost";

    private final AtomicLong value = new AtomicLong(0);

    // 생성자에서 즉시 등록한다 — 갱신 스케줄러가 한 번도 안 돈 기동 직후에도 이름이 0으로 노출돼야 한다.
    public RunPositionLostMetrics(MeterRegistry registry) {
        Gauge.builder(METRIC, value, AtomicLong::get)
                .description("위치가 2분 이상 미수신인 운행 중 회차 수")
                .register(registry);
    }

    /** 최근 집계값으로 게이지를 바꾼다. */
    public void update(long count) {
        value.set(count);
    }
}
