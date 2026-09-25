package src.backend.global.websocket;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.entity.RiderStatus;
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
                // absent(다른 버스로 옮긴 removed 포함)는 이 버스에 없다 — 학생 채널은 학생 단위라 보내면 다른 버스와 섞인다
                .filter(rider -> rider.getStatus() != RiderStatus.ABSENT)
                .map(RunRider::getStudentId)
                .distinct()
                .toList();
        String runStatus = RunStatus.FINISHED.name().toLowerCase(Locale.ROOT);
        gateway.broadcastToRunChannels(event.runId(), event.academyId(), studentIds, EVENT, event.finishedAt(),
                new StudentPayload(runStatus, event.finishedAt()),
                new Payload(runStatus, event.finishedAt(), event.autoAlightedCount()));
    }

    /**
     * {@code run_status}(고정값 {@code finished}) · {@code finished_at} · {@code auto_alighted_count}
     * — 매니저·관제·관리자 채널.
     */
    private record Payload(String runStatus, OffsetDateTime finishedAt, long autoAlightedCount) {
    }

    /** 학생 채널용 — 인원수를 싣지 않는다(C-08 "탑승 인원 미표시", Ruling 335). */
    private record StudentPayload(String runStatus, OffsetDateTime finishedAt) {
    }
}
