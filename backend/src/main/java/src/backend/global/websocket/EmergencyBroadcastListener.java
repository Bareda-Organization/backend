package src.backend.global.websocket;

import java.time.OffsetDateTime;
import java.util.Locale;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;

import src.backend.exception.event.EmergencyAckedEvent;
import src.backend.exception.event.EmergencyCanceledEvent;
import src.backend.exception.event.EmergencyRaisedEvent;

/**
 * 비상 신고 접수·확인·취소 방송(EXC-04, Phase 11 T2 목표 6·10·11) — {@code @TransactionalEventListener
 * (AFTER_COMMIT)} 근거는 {@link RunStartedBroadcastListener} 와 같다.
 *
 * <p><b>{@link WebSocketBroadcastGateway#broadcastToRunChannels} 를 쓰지 않는다</b> — 그 메서드는
 * 회차 명단 학생 채널({@code /topic/students/{id}/run})까지 항상 포함하는데, 목표 7(학부모·학생
 * 계정은 비상 알림을 받지 않는다)이 이 리스너에도 그대로 적용된다. 채널 4종 팬아웃을 그대로
 * 재사용하면 학생 채널로 비상 상황이 새 나가 목표 7을 어긴다 — 그래서 이 리스너는
 * {@link WebSocketBroadcastGateway#send} 를 채널별로 직접 호출해 학생 채널을 구조적으로 뺀다.
 *
 * <p>확인(ack) 방송에만 {@code managerRun(runId)} 를 더한다 — 목표 10 "발신자 앱에 확인이
 * 반영된다" 를 만족하는 자리가 여기다. 접수·취소는 발신자 자신이 이미 그 REST 응답으로 결과를
 * 아는 동작이라 자신의 채널에 다시 쏠 필요가 없다.
 */
@Component
@RequiredArgsConstructor
public class EmergencyBroadcastListener {

    private static final String RAISED_EVENT = "emergency_raised";

    private static final String ACKED_EVENT = "emergency_acked";

    private static final String CANCELED_EVENT = "emergency_canceled";

    private final WebSocketBroadcastGateway gateway;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void broadcastRaised(EmergencyRaisedEvent event) {
        RaisedPayload payload = new RaisedPayload(event.emergencyId(), event.busNo(),
                event.type().name().toLowerCase(Locale.ROOT), event.raisedAt());
        sendToStaffAndAdmin(event.academyId(), event.runId(), RAISED_EVENT, event.raisedAt(), payload);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void broadcastAcked(EmergencyAckedEvent event) {
        AckedPayload payload = new AckedPayload(event.emergencyId(), event.ackedBy(), event.ackedAt());
        gateway.send(WebSocketDestinations.managerRun(event.runId()), ACKED_EVENT, event.runId(), event.ackedAt(),
                payload);
        sendToStaffAndAdmin(event.academyId(), event.runId(), ACKED_EVENT, event.ackedAt(), payload);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void broadcastCanceled(EmergencyCanceledEvent event) {
        CanceledPayload payload = new CanceledPayload(event.emergencyId(), event.busNo(), event.canceledAt());
        sendToStaffAndAdmin(event.academyId(), event.runId(), CANCELED_EVENT, event.canceledAt(), payload);
    }

    private void sendToStaffAndAdmin(Long academyId, Long runId, String eventName, OffsetDateTime occurredAt,
            Object payload) {
        gateway.send(WebSocketDestinations.academyLive(academyId), eventName, runId, occurredAt, payload);
        gateway.send(WebSocketDestinations.ADMIN_LIVE, eventName, runId, occurredAt, payload);
    }

    private record RaisedPayload(Long emergencyId, String busNo, String type, OffsetDateTime raisedAt) {
    }

    private record AckedPayload(Long emergencyId, Long ackedBy, OffsetDateTime ackedAt) {
    }

    private record CanceledPayload(Long emergencyId, String busNo, OffsetDateTime canceledAt) {
    }
}
