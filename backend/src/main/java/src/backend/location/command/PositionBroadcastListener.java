package src.backend.location.command;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.global.websocket.WebSocketBroadcastGateway;
import src.backend.global.websocket.WebSocketDestinations;
import src.backend.location.event.RunPositionReceivedEvent;

/**
 * {@code position} 방송(API_SPEC §7·§7.1, 목표 4·6) — 채널 3종뿐이다. {@code /ws/manager/runs/{id}}
 * 는 §7 채널 표에 {@code position} 이 없어 제외한다(매니저 채널은 {@code rider_changed}·{@code
 * stop_arrived}·{@code run_started}·{@code run_ended}·{@code emergency_acked} 만 받는다).
 * {@code @TransactionalEventListener(AFTER_COMMIT)} 근거는 {@code run.command.RunStartedBroadcastListener} 와 같다.
 *
 * <p><b>학부모·학생 채널과 관제 채널(academy·admin)은 페이로드 타입 자체가 다르다</b>(C-08) —
 * {@code eta} 는 값을 {@code null} 로 비우는 것이 아니라 <b>레코드 컴포넌트 자체를 두지 않아야</b>
 * 학부모·학생 쪽 JSON 에서 키가 사라진다({@link ParentStudentPayload}). 관제 쪽만 그 컴포넌트를 가진
 * {@link ControlPayload} 를 쓴다.
 *
 * <p>관제 채널의 {@code eta} 는 {@code run_stop.eta} 저장값을 그대로 읽은 계획값이다 — 좌표·거리로
 * 다시 계산하지 않는다(Ruling 232 확정 — 계획값, 재계산 부재).
 *
 * <p>{@code current_stop_name}·{@code eta}·학원 id 는 이벤트에 실려 온다 — 위치를 저장한 트랜잭션이 한 번
 * 계산해 두 리스너(이 방송 · Redis 최신 좌표)가 같은 값을 쓴다(BR-100). 전에는 두 리스너가 커밋 뒤 같은
 * 회차·확정 노선·정차 목록·정차지 이름을 각자 다시 읽어 위치 1건(2초마다)에 SELECT 가 약 9개였다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PositionBroadcastListener {

    private static final String EVENT = "position";

    private final RunRiderRepository runRiderRepository;

    private final WebSocketBroadcastGateway gateway;

    /**
     * 위치 1건을 채널별로 다른 페이로드 타입으로 갈라 방송한다(관제 쪽만 {@code eta} 포함). 발행이 트랜잭션 밖이어도
     * 돈다({@code fallbackExecution}) — {@link RunPositionCommandService} 가 커밋을 끝낸 뒤 발행하기 때문이다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void broadcast(RunPositionReceivedEvent event) {
        try {
            // absent(다른 버스로 옮긴 removed 포함)는 이 버스에 없다 — 학생 채널은 학생 단위라 보내면 다른 버스와 섞인다
            List<Long> studentIds = runRiderRepository.findStudentIdsByRunIdAndStatusNot(event.runId(), RiderStatus.ABSENT);

            ParentStudentPayload parentStudentPayload = new ParentStudentPayload(event.lat(), event.lng(),
                    event.receivedAt(), event.currentStopName());
            for (Long studentId : studentIds) {
                gateway.send(WebSocketDestinations.studentRun(studentId), EVENT, event.runId(), event.receivedAt(),
                        parentStudentPayload);
            }

            ControlPayload controlPayload = new ControlPayload(event.lat(), event.lng(), event.receivedAt(),
                    event.currentStopName(), event.nextEta());
            gateway.send(WebSocketDestinations.academyLive(event.academyId()), EVENT, event.runId(), event.receivedAt(),
                    controlPayload);
            gateway.send(WebSocketDestinations.ADMIN_LIVE, EVENT, event.runId(), event.receivedAt(), controlPayload);
        } catch (RuntimeException e) {
            log.warn("position 방송 실패 — runId={}", event.runId(), e);
        }
    }

    /** {@code lat} · {@code lng} · {@code received_at} · {@code current_stop_name}. {@code eta} 키 자체가 없다(C-08). */
    private record ParentStudentPayload(BigDecimal lat, BigDecimal lng, OffsetDateTime receivedAt,
            String currentStopName) {
    }

    /**
     * 위 4개에 {@code eta} 를 더한다 — 관제 채널(academy·admin) 전용. 다음 정차 항목의 계획값이다
     * (Ruling 232 확정) — 남은 정차가 없으면(전 구간 도착 완료) {@code null}.
     */
    private record ControlPayload(BigDecimal lat, BigDecimal lng, OffsetDateTime receivedAt, String currentStopName,
            OffsetDateTime eta) {
    }
}
