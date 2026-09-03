package src.backend.observability.metrics;

import java.time.Duration;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * 도메인 이벤트 발생 시각부터 WebSocket 발행 완료까지의 지연을 계측한다({@code TECH_DECISIONS §13.1}).
 *
 * <p>{@code WebSocketBroadcastGateway} 가 채널마다 이미 갖고 있는 {@code occurredAt}(원본 이벤트
 * 시각)을 그대로 넘겨받아 발행 시각과의 차를 잰다 — 리스너 8곳을 고칠 필요가 없다.
 */
@Component
public class WebSocketPublishMetrics {

    private static final String LATENCY_METRIC = "schoolbus.websocket.publish.latency";

    private final Timer latencyTimer;

    // 생성자에서 즉시 등록한다 — 발행이 한 번도 없는 기동 직후에도 이름이 노출돼야 한다.
    public WebSocketPublishMetrics(MeterRegistry registry) {
        this.latencyTimer = Timer.builder(LATENCY_METRIC)
                .description("도메인 이벤트 발생부터 WebSocket 발행 완료까지의 지연")
                .register(registry);
    }

    public void recordLatency(Duration latency) {
        latencyTimer.record(latency);
    }
}
