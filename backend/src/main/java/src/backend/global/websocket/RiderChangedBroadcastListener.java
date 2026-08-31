package src.backend.global.websocket;

import java.time.OffsetDateTime;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.event.RiderStatusChangedEvent;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.student.repository.StudentRepository;

/**
 * {@code rider_changed} 방송(API_SPEC §7.1) — 매니저·학원·관리자 채널(목표 5, C-08·§1.12: 학부모·
 * 학생 채널은 이 이벤트를 받지 않는다). {@code @TransactionalEventListener(AFTER_COMMIT)} 근거는
 * {@link RunStartedBroadcastListener} 와 같다.
 *
 * <p>{@code stop_skipped} 는 이 리스너가 항상 {@code false} 로 채운다 — 정차지 skip 은 미승차로 잔여가
 * 0명이 될 때만 일어나는데({@code BoardingCommandService.handleNoShow}), 그 경로는
 * {@link RiderStatusChangedEvent} 를 발행하지 않고 {@code RiderNoShowEvent} 만 발행한다(현재 이
 * 리스너의 미도달 갈래). 그 경로의 {@code rider_changed} 방송은 다루지 않았다 — 확신 없는 지점으로
 * 보고에 남긴다(②항).
 */
@Component
@RequiredArgsConstructor
public class RiderChangedBroadcastListener {

    private static final String EVENT = "rider_changed";

    private final StudentRepository studentRepository;

    private final RunRiderRepository runRiderRepository;

    private final WebSocketBroadcastGateway gateway;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void broadcast(RiderStatusChangedEvent event) {
        String studentName = studentRepository.findById(event.studentId())
                .map(student -> student.getName())
                .orElse(null);
        Long stopId = runRiderRepository.findByRunIdAndStudentId(event.runId(), event.studentId())
                .map(rider -> rider.getStopId())
                .orElse(null);
        Counts counts = countsOf(event.runId());

        Payload payload = new Payload(event.runRiderId(), event.studentId(), studentName, event.status(), stopId,
                event.changedAt(), counts, false);
        gateway.send(WebSocketDestinations.managerRun(event.runId()), EVENT, event.runId(), event.changedAt(),
                payload);
        gateway.send(WebSocketDestinations.academyLive(event.academyId()), EVENT, event.runId(), event.changedAt(),
                payload);
        gateway.send(WebSocketDestinations.ADMIN_LIVE, EVENT, event.runId(), event.changedAt(), payload);
    }

    private Counts countsOf(Long runId) {
        return new Counts(
                runRiderRepository.countByRunIdAndStatus(runId, RiderStatus.WAITING),
                runRiderRepository.countByRunIdAndStatus(runId, RiderStatus.BOARDED),
                runRiderRepository.countByRunIdAndStatus(runId, RiderStatus.ALIGHTED),
                runRiderRepository.countByRunIdAndStatus(runId, RiderStatus.ABSENT),
                runRiderRepository.countByRunIdAndStatus(runId, RiderStatus.NO_SHOW));
    }

    /**
     * {@code rider_id} · {@code student_id} · {@code student_name} · {@code status} · {@code stop_id} ·
     * {@code changed_at} · {@code counts} · {@code stop_skipped}.
     */
    private record Payload(Long riderId, Long studentId, String studentName, String status, Long stopId,
            OffsetDateTime changedAt, Counts counts, boolean stopSkipped) {
    }

    /** 그 회차 전체 탑승자를 상태별로 센 값 — 5종({@link RiderStatus}) 전부 채운다. */
    private record Counts(long waiting, long boarded, long alighted, long absent, long noShow) {
    }
}
