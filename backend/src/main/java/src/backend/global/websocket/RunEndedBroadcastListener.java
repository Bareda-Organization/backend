package src.backend.global.websocket;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.entity.RunRider;
import src.backend.boarding.event.RunEndedEvent;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.run.entity.RunStatus;

/**
 * {@code run_ended} 방송(API_SPEC §7.1) — 채널 4종 전부. {@code @TransactionalEventListener
 * (AFTER_COMMIT)} 근거는 {@link RunStartedBroadcastListener} 와 같다.
 */
@Component
@RequiredArgsConstructor
public class RunEndedBroadcastListener {

    private static final String EVENT = "run_ended";

    private final RunRiderRepository runRiderRepository;

    private final WebSocketBroadcastGateway gateway;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void broadcast(RunEndedEvent event) {
        List<Long> studentIds = runRiderRepository.findAllByRunId(event.runId()).stream()
                .map(RunRider::getStudentId)
                .distinct()
                .toList();
        Payload payload = new Payload(RunStatus.FINISHED.name().toLowerCase(Locale.ROOT), event.finishedAt(),
                event.autoAlightedCount());
        gateway.broadcastToRunChannels(event.runId(), event.academyId(), studentIds, EVENT, event.finishedAt(),
                payload);
    }

    /** {@code run_status}(고정값 {@code finished}) · {@code finished_at} · {@code auto_alighted_count}. */
    private record Payload(String runStatus, OffsetDateTime finishedAt, long autoAlightedCount) {
    }
}
