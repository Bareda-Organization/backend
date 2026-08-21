package src.backend.observability.listener;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import src.backend.location.event.BusLocationUpdatedEvent;
import src.backend.observability.metrics.PipelineMetrics;

/**
 * 버스 좌표 수신을 계측한다. {@code BusLocationCommandService} 가 저장 직후 이미 발행하는 이벤트를
 * 구독할 뿐이라 도메인 코드에 계측 호출을 넣지 않는다.
 *
 * <p>Kafka 로 나가는 push 경로와 같은 이벤트를 쓰지만, 이쪽은 애플리케이션 내부 리스너라
 * Kafka 가 죽어도 수신 계측은 계속 동작한다.
 *
 * <p>plain {@code @EventListener} 가 아니라 {@code AFTER_COMMIT} 을 쓰는 이유는 이 저장소가
 * {@code TransactionalDomainEventRelay} 에서 이미 지킨 규칙과 같다 — 커밋 전에 세면 롤백된
 * 저장까지 "수신"으로 잡혀 수신율이 거짓이 된다. {@code BusLocationCommandService.ingest()} 는
 * 자신이 {@code @Transactional} 이라 REST 보고(reportSelf)·서버 Mock 소스(MockBusLocationSource.tick)
 * 양쪽 호출 경로 모두 트랜잭션 안에서 이벤트를 발행하므로 fallbackExecution 은 필요 없다 — 트랜잭션 밖
 * 발행이 생기면 이 리스너가 아예 돌지 않는다는 점을 유의한다.
 */
@Component
public class BusLocationMetricsListener {

    private final PipelineMetrics metrics;

    public BusLocationMetricsListener(PipelineMetrics metrics) {
        this.metrics = metrics;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBusLocationUpdated(BusLocationUpdatedEvent event) {
        metrics.busLocationReported(event.origin());
    }
}
