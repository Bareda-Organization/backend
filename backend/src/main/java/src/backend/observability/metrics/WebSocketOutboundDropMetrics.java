package src.backend.observability.metrics;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 팬아웃 실행기 큐 포화로 방송을 버린 건수를 이벤트 태그로 센다(BR-170).
 *
 * <p>{@code event} 태그가 갈리지만 지금 버려지는 종류는 {@code position} 뿐이다(Ruling 349 —
 * 위치는 2~5초 뒤 새 값이 덮어쓰는 데이터라 버려도 되고, 승하차·비상은 버리지 않는다). 다른
 * 이벤트가 버려지는 경로가 생기면 그때 태그값을 더한다(YAGNI) — 지금은 있지도 않은 종류를
 * 미리 세지 않는다.
 */
@Component
public class WebSocketOutboundDropMetrics {

    private static final String DROPPED_METRIC = "schoolbus.ws.outbound.dropped";

    private final Counter positionDroppedCounter;

    // 생성자에서 즉시 등록한다 — 버려진 건이 한 번도 없는 기동 직후에도 이름이 노출돼야 한다.
    public WebSocketOutboundDropMetrics(MeterRegistry registry) {
        this.positionDroppedCounter = Counter.builder(DROPPED_METRIC)
                .description("팬아웃 실행기 큐가 가득 차 버려진 방송 건수 (event 태그, Ruling 349 로 position 만 버려짐)")
                .tag("event", "position")
                .register(registry);
    }

    /** 큐 포화로 위치(position) 방송 1건을 버렸다. */
    public void recordPositionDropped() {
        positionDroppedCounter.increment();
    }
}
