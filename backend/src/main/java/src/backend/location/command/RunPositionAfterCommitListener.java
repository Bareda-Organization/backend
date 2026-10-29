package src.backend.location.command;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;

import src.backend.global.common.logging.RateLimitedWarn;
import src.backend.location.event.RunPositionReceivedEvent;

/**
 * 커밋된 {@link RunPositionReceivedEvent} 를 Redis 최신 좌표 갱신 → 방송 순서로 처리한다 — 갱신이 더 새로운 위치에 막힌 옛 위치는
 * 방송하지 않는다. 두 구독자가 따로 돌면 늦게 커밋된 옛 위치가 Redis 는 못 바꿔도 방송은 나가 지도의 버스가 뒤로 간다.
 *
 * <p>발행이 트랜잭션 밖이어도 돈다({@code fallbackExecution}) — {@link RunPositionCommandService} 가 커밋을 끝낸 뒤 발행하기 때문이다.
 * 같은 이유로 이 리스너의 예외는 송신 요청까지 올라가므로, 방송 실패는 여기서 WARN 한 줄로 남기고 삼킨다(CODE_CONVENTIONS §6.1) — 이력과 Redis
 * 최신 좌표는 이미 반영됐고 방송은 다음 송신이 이어 간다.
 */
@Component
@RequiredArgsConstructor
public class RunPositionAfterCommitListener {

    private static final Logger log = LoggerFactory.getLogger(RunPositionAfterCommitListener.class);

    private final RunPositionRedisListener redisListener;

    private final PositionBroadcastListener broadcastListener;

    /** 브로커나 Redis 가 멈추면 송신마다 같은 실패가 난다 — 분당 한 번만 스택과 함께 남긴다. */
    private final RateLimitedWarn broadcastFailure = RateLimitedWarn.perMinute(log);

    /** 최신 좌표를 갱신하고, 이 위치가 가장 새로우면(또는 Redis 로 판단할 수 없으면) 방송한다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPositionReceived(RunPositionReceivedEvent event) {
        if (redisListener.updateRedisAfterCommit(event)) {
            try {
                broadcastListener.broadcast(event);
            } catch (RuntimeException e) {
                broadcastFailure.warn(e, "[location] position 방송이 실패했다 — 다음 송신이 이어 방송한다. runId={}", event.runId());
            }
        }
    }
}
