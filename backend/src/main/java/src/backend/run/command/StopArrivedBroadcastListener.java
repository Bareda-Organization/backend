package src.backend.run.command;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.global.websocket.WebSocketBroadcastGateway;
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

    /** 커밋 콜백이 요청 스레드의 트랜잭션 · 커넥션을 쥐고 방송하지 않도록 방송 작업을 넘기는 전용 실행기(R57 RT-4) — 단일 스레드라 커밋 순서가 유지된다. */
    @Qualifier("broadcastListenerExecutor")
    private final Executor broadcastExecutor;

    /** 승하차지 도착을 학생·매니저·학원·관리자 채널 4종 전부에 방송한다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void broadcast(StopArrivedEvent event) {
        broadcastExecutor.execute(() -> deliverStopArrivedEvent(event));
    }

    private void deliverStopArrivedEvent(StopArrivedEvent event) {
        List<Long> studentIds = runRiderRepository.findAllByRunId(event.runId()).stream()
                // absent(다른 버스로 옮긴 removed 포함)는 이 버스에 없다 — 다른 방송 리스너와 같은 필터(BR-233)
                .filter(rider -> rider.getStatus() != RiderStatus.ABSENT)
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
