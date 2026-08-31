package src.backend.global.websocket;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.run.entity.RunStatus;
import src.backend.run.event.RunStartedEvent;

/**
 * {@code run_started} 방송(API_SPEC §7.1) — 채널 4종 전부(학생 개인 채널은 회차 명단 전원에게
 * 팬아웃, §7 채널 표).
 *
 * <p>{@code @TransactionalEventListener(AFTER_COMMIT)} 인 이유는 {@link src.backend.global.event
 * .TransactionalDomainEventRelay} 의 Kafka 릴레이와 같다 — WS 발신은 outbox 행 적재(알림 모듈의
 * 평범한 {@code @EventListener})와 달리 <b>같은 트랜잭션 롤백으로 되돌릴 수 없는 외부 부수효과</b>다.
 * 커밋 전에 보내면, 그 뒤 트랜잭션이 롤백됐을 때 "일어나지 않은 회차 시작"이 관제 화면에 남는다.
 */
@Component
@RequiredArgsConstructor
public class RunStartedBroadcastListener {

    private static final String EVENT = "run_started";

    private final RunRiderRepository runRiderRepository;

    private final WebSocketBroadcastGateway gateway;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void broadcast(RunStartedEvent event) {
        List<Long> studentIds = runRiderRepository.findAllByRunId(event.runId()).stream()
                .map(RunRider::getStudentId)
                .distinct()
                .toList();
        Payload payload = new Payload(RunStatus.MOVING.name().toLowerCase(Locale.ROOT), event.startedAt(),
                event.autoBoardedCount());
        gateway.broadcastToRunChannels(event.runId(), event.academyId(), studentIds, EVENT, event.startedAt(),
                payload);
    }

    /** {@code run_status}(고정값 {@code moving}) · {@code started_at} · {@code auto_boarded_count}. */
    private record Payload(String runStatus, OffsetDateTime startedAt, int autoBoardedCount) {
    }
}
