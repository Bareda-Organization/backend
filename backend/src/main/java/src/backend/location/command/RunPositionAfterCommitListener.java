package src.backend.location.command;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;

import src.backend.location.event.RunPositionReceivedEvent;

/**
 * 커밋된 {@link RunPositionReceivedEvent} 를 Redis 최신 좌표 갱신 → 방송 순서로 처리한다 — 갱신이 더 새로운 위치에 막힌 옛 위치는
 * 방송하지 않는다. 두 구독자가 따로 돌면 늦게 커밋된 옛 위치가 Redis 는 못 바꿔도 방송은 나가 지도의 버스가 뒤로 간다.
 *
 * <p>발행이 트랜잭션 밖이어도 돈다({@code fallbackExecution}) — {@link RunPositionCommandService} 가 커밋을 끝낸 뒤 발행하기 때문이다.
 */
@Component
@RequiredArgsConstructor
public class RunPositionAfterCommitListener {

    private final RunPositionRedisListener redisListener;

    private final PositionBroadcastListener broadcastListener;

    /** 최신 좌표를 갱신하고, 이 위치가 가장 새로우면(또는 Redis 로 판단할 수 없으면) 방송한다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPositionReceived(RunPositionReceivedEvent event) {
        if (redisListener.updateRedisAfterCommit(event)) {
            broadcastListener.broadcast(event);
        }
    }
}
