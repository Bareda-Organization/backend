package src.backend.global.websocket;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.run.event.StopArrivedEvent;

/**
 * {@code stop_arrived} 방송(API_SPEC §7.1) — 채널 4종 전부. {@code @TransactionalEventListener
 * (AFTER_COMMIT)} 근거는 {@link RunStartedBroadcastListener} 와 같다.
 */
@Component
@RequiredArgsConstructor
public class StopArrivedBroadcastListener {

    private static final String EVENT = "stop_arrived";

    private final RunRiderRepository runRiderRepository;

    private final WebSocketBroadcastGateway gateway;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void broadcast(StopArrivedEvent event) {
        List<Long> studentIds = runRiderRepository.findAllByRunId(event.runId()).stream()
                .map(RunRider::getStudentId)
                .distinct()
                .toList();
        Payload payload = new Payload(event.stopId(), event.seq(), event.name(), event.arrivedAt(),
                event.nextStopId());
        gateway.broadcastToRunChannels(event.runId(), event.academyId(), studentIds, EVENT, event.arrivedAt(),
                payload);
    }

    /** {@code stop_id} · {@code seq} · {@code name} · {@code arrived_at} · {@code next_stop_id}. */
    private record Payload(Long stopId, int seq, String name, OffsetDateTime arrivedAt, Long nextStopId) {
    }
}
