package src.backend.academy.command;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;

import src.backend.academy.event.StaffResignedEvent;
import src.backend.global.security.AccountStandingEvictionListener;

/** 관계자 퇴사가 커밋되면 그 계정의 권한 상태 보관 항목을 모든 인스턴스에서 비운다 — 이미 받은 access 토큰이 다음 요청부터 거절되게 한다(Ruling 880 · 899 A). */
@Component
@RequiredArgsConstructor
class StaffResignedStandingListener {

    private final AccountStandingEvictionListener standingEviction;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onResigned(StaffResignedEvent event) {
        standingEviction.evictEverywhere(event.accountId());
    }
}
