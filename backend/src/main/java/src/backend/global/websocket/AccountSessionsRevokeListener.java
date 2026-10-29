package src.backend.global.websocket;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.extern.slf4j.Slf4j;

import src.backend.global.cache.CacheEvictionRelay;

/**
 * 채널 권한이 바뀐 계정의 열린 세션을 변경이 커밋된 직후 닫는다 — 롤백된 변경으로는 닫지 않는다. 세션은 인스턴스마다 따로 쥐고 있으므로 이 인스턴스의 세션을 닫은 뒤
 * 같은 신호를 다른 인스턴스에도 알려 각자 자기 세션을 닫게 한다(Redis 가 멈춰도 이 인스턴스의 세션은 이미 닫혔다).
 */
@Component
@Slf4j
public class AccountSessionsRevokeListener {

    private final OpenWebSocketSessions openSessions;

    private final CacheEvictionRelay relay;

    public AccountSessionsRevokeListener(OpenWebSocketSessions openSessions, CacheEvictionRelay relay) {
        this.openSessions = openSessions;
        this.relay = relay;
        relay.register(CacheEvictionRelay.SESSIONS_REVOKE, accountId -> openSessions.revoke(List.of(accountId)));
    }

    /** 영향받은 계정의 세션을 이 인스턴스에서 닫고 다른 인스턴스에 알린다 — 이 인스턴스의 닫기가 실패해도 알림은 나간다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void revoke(AccountSessionsRevokeEvent event) {
        try {
            openSessions.revoke(event.accountIds());
        } catch (RuntimeException e) {
            // 커밋 뒤 리스너는 WARN 으로 남기고 삼킨다 — 이 인스턴스의 닫기가 실패해도 다른 인스턴스에는 알려야 한다
            log.warn("권한이 바뀐 계정의 WebSocket 세션을 이 인스턴스에서 닫지 못했다 — accountIds={}", event.accountIds(), e);
        }
        event.accountIds().forEach(accountId -> relay.publish(CacheEvictionRelay.SESSIONS_REVOKE, accountId));
    }
}
