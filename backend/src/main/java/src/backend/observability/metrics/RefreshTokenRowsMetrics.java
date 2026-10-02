package src.backend.observability.metrics;

import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 보존 정리를 마친 직후 {@code refresh_token} 에 남은 행 수 게이지(R47, Ruling 742 — R46 누수 검토 R-4). 재발급마다 행이 하나 늘고
 * 만료·폐기 30일 뒤에 지워지므로 정상 상태의 크기는 하루 발급 수 × 30 이다 — 이 값이 계속 오르면 정리가 발급을 따라가지 못하는 것이다.
 */
@Component
public class RefreshTokenRowsMetrics {

    private static final String METRIC = "schoolbus.refresh.token.rows";

    // 기동 직후(정리가 한 번도 안 돈 동안)는 0 이 아니라 NaN — 0 이면 빈 테이블로 읽힌다. 이름은 기동 때부터 노출된다.
    private final AtomicReference<Long> rows = new AtomicReference<>();

    public RefreshTokenRowsMetrics(MeterRegistry registry) {
        Gauge.builder(METRIC, rows, ref -> ref.get() == null ? Double.NaN : ref.get())
                .description("보존 정리 직후 refresh_token 남은 행 수(하루 1회 갱신)")
                .register(registry);
    }

    /** 정리 직후 센 행 수로 게이지를 바꾼다. */
    public void update(long count) {
        rows.set(count);
    }
}
