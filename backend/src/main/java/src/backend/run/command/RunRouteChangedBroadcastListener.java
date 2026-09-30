package src.backend.run.command;

import java.time.OffsetDateTime;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import src.backend.global.websocket.WebSocketBroadcastGateway;
import src.backend.global.websocket.WebSocketDestinations;
import src.backend.run.event.RunRouteConfirmedEvent;

/**
 * {@code route_changed} 방송(API_SPEC §7.1, Ruling 373) — 확정 노선이 새 판본으로 바뀐 것을 <b>매니저 채널에만</b>
 * 알린다. 받은 매니저 앱이 노선·명단을 다시 불러오므로 본문에 노선을 싣지 않는다. 관제·관리자 채널은 폴링이 있어
 * 대상 밖이라 {@link WebSocketBroadcastGateway#broadcastToRunChannels} 를 쓰지 않는다
 * ({@code exception.command.EmergencyBroadcastListener#broadcastAcked} 와 같은 이유).
 *
 * <p>{@code @TransactionalEventListener(AFTER_COMMIT)} 근거는 {@link RunStartedBroadcastListener} 와 같다 —
 * 롤백된 확정이 화면에 남지 않게 한다. 방송 실패는 여기서 WARN 한 줄로 기록하고 삼킨다 — 삼키지 않아도 응답은
 * 바뀌지 않지만 스프링이 오류 스택으로 남긴다(2026-09-30 F8 관측).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunRouteChangedBroadcastListener {

    private static final String EVENT = "route_changed";

    private final WebSocketBroadcastGateway gateway;

    /** 노선 확정 이벤트를 매니저 채널 1곳에 방송한다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void broadcast(RunRouteConfirmedEvent event) {
        try {
            gateway.send(WebSocketDestinations.managerRun(event.runId()), EVENT, event.runId(), event.confirmedAt(),
                    new Payload(event.runId(), event.confirmedAt()));
        } catch (RuntimeException e) {
            log.warn("route_changed 방송 실패 — runId={}", event.runId(), e);
        }
    }

    /** {@code run_id} · {@code changed_at}. */
    private record Payload(Long runId, OffsetDateTime changedAt) {
    }
}
