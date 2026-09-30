package src.backend.request.command;

import java.time.OffsetDateTime;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import src.backend.boarding.repository.RunRiderRepository;
import src.backend.global.websocket.WebSocketBroadcastGateway;
import src.backend.global.websocket.WebSocketDestinations;
import src.backend.request.event.ApprovalRequestedEvent;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

/**
 * {@code approval_requested} 방송(API_SPEC §7.1) — 학원 관제 채널 전용(다른 3채널은 이 이벤트를
 * 받지 않는다, §7 채널 표). {@link ApprovalRequestedEvent} 자체는 두 발행처
 * ({@code ChangeRequestStore}·{@code BoardingIntentCommandService})가 갈라져 있고 각자
 * {@code student_name}·{@code stop_name}·{@code deadline_at} 을 일관되게 계산해 줄 공통 지점이
 * 없어, 이벤트를 확장하지 않고 이 리스너가 읽기 전용 조회 3건으로 채운다(판단 근거 — 보고 ①항).
 *
 * <p>{@code @TransactionalEventListener(AFTER_COMMIT)} 근거는 {@code run.command.RunStartedBroadcastListener}
 * 와 같다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApprovalRequestedBroadcastListener {

    private static final String EVENT = "approval_requested";

    private final StudentRepository studentRepository;

    private final RunRiderRepository runRiderRepository;

    private final StopRepository stopRepository;

    private final ChangeRequestRepository changeRequestRepository;

    private final WebSocketBroadcastGateway gateway;

    /** 커밋 후 학생·정차지·마감을 채워 {@code approval_requested} 를 학원 관제 채널에 방송한다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void broadcast(ApprovalRequestedEvent event) {
        try {
            String studentName = studentRepository.findById(event.studentId())
                    .map(student -> student.getName())
                    .orElse(null);
            String stopName = runRiderRepository.findByRunIdAndStudentId(event.runId(), event.studentId())
                    .map(rider -> stopRepository.findById(rider.getStopId()).map(stop -> stop.getName()).orElse(null))
                    .orElse(null);
            OffsetDateTime deadlineAt = changeRequestRepository.findById(event.changeRequestId())
                    .map(changeRequest -> changeRequest.getDeadlineAt())
                    .orElse(null);

            Payload payload = new Payload(event.changeRequestId(), studentName, event.runId(), stopName, deadlineAt);
            gateway.send(WebSocketDestinations.academyLive(event.academyId()), EVENT, event.runId(), event.requestedAt(),
                    payload);
        } catch (RuntimeException e) {
            log.warn("approval_requested 방송 실패 — runId={}", event.runId(), e);
        }
    }

    /** {@code approval_id} · {@code student_name} · {@code run_id} · {@code stop_name} · {@code deadline_at}. */
    private record Payload(Long approvalId, String studentName, Long runId, String stopName,
            OffsetDateTime deadlineAt) {
    }
}
