package src.backend.observability.listener;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import src.backend.location.event.BusLocationUpdatedEvent;
import src.backend.observability.metrics.PipelineMetrics;

/**
 * 버스 좌표 수신을 계측한다. {@code BusLocationCommandService} 가 저장 직후 이미 발행하는 이벤트를
 * 구독할 뿐이라 도메인 코드에 계측 호출을 넣지 않는다.
 *
 * <p>Kafka 로 나가는 push 경로와 같은 이벤트를 쓰지만, 이쪽은 애플리케이션 내부 리스너라
 * Kafka 가 죽어도 수신 계측은 계속 동작한다.
 */
@Component
public class BusLocationMetricsListener {

    private final PipelineMetrics metrics;

    public BusLocationMetricsListener(PipelineMetrics metrics) {
        this.metrics = metrics;
    }

    @EventListener
    public void onBusLocationUpdated(BusLocationUpdatedEvent event) {
        metrics.busLocationReported(event.origin());
    }
}
