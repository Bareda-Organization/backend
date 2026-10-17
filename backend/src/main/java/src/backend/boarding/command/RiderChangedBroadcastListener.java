package src.backend.boarding.command;

import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.event.RiderMarkedNoShowEvent;
import src.backend.boarding.event.RiderStatusChangedEvent;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.global.websocket.BroadcastTask;
import src.backend.global.websocket.WebSocketBroadcastGateway;
import src.backend.global.websocket.WebSocketDestinations;
import src.backend.student.repository.StudentRepository;

/**
 * {@code rider_changed} 방송(API_SPEC §7.1) — 매니저·학원·관리자 채널(목표 5, C-08·§1.12: 학부모·
 * 학생 채널은 이 이벤트를 받지 않는다). {@code @TransactionalEventListener(AFTER_COMMIT)} 근거는
 * {@code run.command.RunStartedBroadcastListener} 와 같다.
 *
 * <p>{@link RiderStatusChangedEvent} 갈래는 {@code stop_skipped} 를 항상 {@code false} 로 채운다 —
 * 정차지 skip 은 미승차로 잔여가 0명이 될 때만 일어나는데({@code BoardingCommandService.handleNoShow}),
 * 그 경로는 이 이벤트를 발행하지 않고 {@link RiderMarkedNoShowEvent} 만 발행한다. 그 경로의 방송은
 * {@link #broadcast(RiderMarkedNoShowEvent)} 가 별도로 맡는다(Phase 11, Phase 10 이월 ⑤ 해소 — API_SPEC
 * §7.1 line 1995 의 트리거가 {@code PATCH /runs/{runId}/riders/{riderId}} <b>엔드포인트</b>로 적혀
 * {@code status} 값과 무관하게 걸린다는 근거는 {@link RiderMarkedNoShowEvent} 의 javadoc 참고).
 */
@Component
@RequiredArgsConstructor
public class RiderChangedBroadcastListener {

    private static final String EVENT = "rider_changed";

    private final StudentRepository studentRepository;

    private final RunRiderRepository runRiderRepository;

    private final WebSocketBroadcastGateway gateway;

    /** 커밋 콜백이 요청 스레드의 트랜잭션 · 커넥션을 쥐고 방송하지 않도록 방송 작업을 넘기는 전용 실행기(R57 RT-4) — 단일 스레드라 커밋 순서가 유지된다. */
    @Qualifier("broadcastListenerExecutor")
    private final Executor broadcastExecutor;

    /** 승하차 상태 변경을 매니저·학원·관리자 채널에 방송한다({@code stop_skipped} 는 항상 false). */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void broadcast(RiderStatusChangedEvent event) {
        broadcastExecutor.execute(new BroadcastTask(EVENT, event.runId(), () -> deliverRiderStatusChangedEvent(event)));
    }

    private void deliverRiderStatusChangedEvent(RiderStatusChangedEvent event) {
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

    /**
     * 미승차 경로의 {@code rider_changed} 방송(Phase 11, Phase 10 이월 ⑤ 해소) — {@link RiderMarkedNoShowEvent}
     * 가 이미 {@code stopId} · {@code stopSkipped} 를 들고 있어 {@link RunRiderRepository} 를 다시
     * 조회할 필요가 없다({@code broadcast(RiderStatusChangedEvent)} 와의 유일한 구조적 차이).
     * {@code status} 는 {@code "no_show"} 리터럴이다 — 이 이벤트 자체가 그 상태 전이 하나만 나른다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void broadcast(RiderMarkedNoShowEvent event) {
        broadcastExecutor.execute(new BroadcastTask(EVENT, event.runId(), () -> deliverRiderMarkedNoShowEvent(event)));
    }

    private void deliverRiderMarkedNoShowEvent(RiderMarkedNoShowEvent event) {
        String studentName = studentRepository.findById(event.studentId())
                .map(student -> student.getName())
                .orElse(null);
        Counts counts = countsOf(event.runId());

        Payload payload = new Payload(event.runRiderId(), event.studentId(), studentName, "no_show", event.stopId(),
                event.changedAt(), counts, event.stopSkipped());
        gateway.send(WebSocketDestinations.managerRun(event.runId()), EVENT, event.runId(), event.changedAt(),
                payload);
        gateway.send(WebSocketDestinations.academyLive(event.academyId()), EVENT, event.runId(), event.changedAt(),
                payload);
        gateway.send(WebSocketDestinations.ADMIN_LIVE, EVENT, event.runId(), event.changedAt(), payload);
    }

    /** 상태별 인원을 질의 1번으로 읽는다(R57 RT-4) — 인원이 없는 상태는 0 이다. */
    private Counts countsOf(Long runId) {
        Map<RiderStatus, Long> totals = new EnumMap<>(RiderStatus.class);
        runRiderRepository.countGroupedByStatus(runId).forEach(row -> totals.put(row.getStatus(), row.getTotal()));
        return new Counts(
                totals.getOrDefault(RiderStatus.WAITING, 0L),
                totals.getOrDefault(RiderStatus.BOARDED, 0L),
                totals.getOrDefault(RiderStatus.ALIGHTED, 0L),
                totals.getOrDefault(RiderStatus.ABSENT, 0L),
                totals.getOrDefault(RiderStatus.NO_SHOW, 0L));
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
