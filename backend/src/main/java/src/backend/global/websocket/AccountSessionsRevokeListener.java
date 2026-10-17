package src.backend.global.websocket;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;

/** 채널 권한이 바뀐 계정의 열린 세션을 변경이 커밋된 직후 닫는다 — 롤백된 변경으로는 닫지 않는다. */
@Component
@RequiredArgsConstructor
public class AccountSessionsRevokeListener {

    private final OpenWebSocketSessions openSessions;

    /** 영향받은 계정의 세션을 닫는다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void revoke(AccountSessionsRevokeEvent event) {
        openSessions.revoke(event.accountIds());
    }
}
