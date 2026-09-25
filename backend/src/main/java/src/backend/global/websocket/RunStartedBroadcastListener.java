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
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.run.entity.RunStatus;
import src.backend.run.event.RunStartedEvent;

/**
 * {@code run_started} 방송(API_SPEC §7.1) — 채널 4종 전부(학생 개인 채널은 회차 명단 전원에게
 * 팬아웃, §7 채널 표).
 *
 * <p>{@code @TransactionalEventListener(AFTER_COMMIT)} 인 이유는 WS 발신이 outbox 행 적재(알림 모듈의
 * 평범한 {@code @EventListener})와 달리 <b>같은 트랜잭션 롤백으로 되돌릴 수 없는 외부 부수효과</b>이기 때문이다.
 * 커밋 전에 보내면, 그 뒤 트랜잭션이 롤백됐을 때 "일어나지 않은 회차 시작"이 관제 화면에 남는다.
 */
@Component
@RequiredArgsConstructor
public class RunStartedBroadcastListener {

    private static final String EVENT = "run_started";

    private final RunRiderRepository runRiderRepository;

    private final WebSocketBroadcastGateway gateway;

    /** 운행 시작을 학생·매니저·학원·관리자 채널 4종 전부에 방송한다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void broadcast(RunStartedEvent event) {
        List<Long> studentIds = runRiderRepository.findAllByRunId(event.runId()).stream()
                // absent(다른 버스로 옮긴 removed 포함)는 이 버스에 없다 — 학생 채널은 학생 단위라 보내면 다른 버스와 섞인다
                .filter(rider -> rider.getStatus() != RiderStatus.ABSENT)
                .map(RunRider::getStudentId)
                .distinct()
                .toList();
        String runStatus = RunStatus.MOVING.name().toLowerCase(Locale.ROOT);
        gateway.broadcastToRunChannels(event.runId(), event.academyId(), studentIds, EVENT, event.startedAt(),
                new StudentPayload(runStatus, event.startedAt()),
                new Payload(runStatus, event.startedAt(), event.autoBoardedCount()));
    }

    /**
     * {@code run_status}(고정값 {@code moving}) · {@code started_at} · {@code auto_boarded_count}
     * — 매니저·관제·관리자 채널.
     */
    private record Payload(String runStatus, OffsetDateTime startedAt, int autoBoardedCount) {
    }

    /** 학생 채널용 — 인원수를 싣지 않는다(C-08 "탑승 인원 미표시", Ruling 335). */
    private record StudentPayload(String runStatus, OffsetDateTime startedAt) {
    }
}
