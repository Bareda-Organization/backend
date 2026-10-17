package src.backend.global.security;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import jakarta.annotation.PostConstruct;

import lombok.RequiredArgsConstructor;

import src.backend.academy.event.StaffResignedEvent;
import src.backend.global.cache.CacheEvictionRelay;
import src.backend.manager.event.ManagerDeletedEvent;

/**
 * 권한 상태가 바뀐 트랜잭션이 커밋된 직후 그 계정의 {@link AccountStanding} 보관 항목을 비운다(Ruling 899 A).
 * 커밋 전에 비우면 그 사이 들어온 요청이 옛 상태를 다시 보관하고, 롤백된 변경으로 비우면 불필요한 질의가 늘 뿐이라 {@code AFTER_COMMIT} 이다.
 * 트랜잭션 없이 낸 이벤트도 비운다({@code fallbackExecution}) — 비움을 빠뜨리는 쪽이 더 나쁘다.
 */
@Component
@RequiredArgsConstructor
public class AccountStandingEvictionListener {

    private final AccountStanding accountStanding;

    private final CacheEvictionRelay evictionRelay;

    /** 다른 인스턴스가 이 계정을 비웠다는 신호가 오면 이 인스턴스의 항목도 비운다(Ruling 900 D). */
    @PostConstruct
    void listenForOtherInstances() {
        evictionRelay.register(CacheEvictionRelay.ACCOUNT_STANDING, accountStanding::evict);
    }

    /** 이 인스턴스의 항목을 바로 비우고, 다른 인스턴스에도 알린다 — 신호를 놓쳐도 보관 시간(5초)이 상한이다. */
    private void evictEverywhere(Long accountId) {
        accountStanding.evict(accountId);
        evictionRelay.publish(CacheEvictionRelay.ACCOUNT_STANDING, accountId);
    }

    /** 로그인 실패 차단 · 차단 해제 · 재직 복귀. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onChanged(AccountStandingChangedEvent event) {
        evictEverywhere(event.accountId());
    }

    /** 관계자 퇴사. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onResigned(StaffResignedEvent event) {
        evictEverywhere(event.accountId());
    }

    /** 매니저 삭제. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onManagerDeleted(ManagerDeletedEvent event) {
        evictEverywhere(event.accountId());
    }
}
