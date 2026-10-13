package src.backend.account.command;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.account.repository.RefreshTokenRepository;
import src.backend.manager.event.ManagerDeletedEvent;

/**
 * 매니저 삭제를 받아 그 계정의 유효 refresh 토큰을 전량 무효화한다(Ruling 891 · 퇴사 처리 §6.7 과 같은 수단).
 *
 * <p>{@code @EventListener}(동기)인 이유 — 삭제를 커밋한 트랜잭션 안에서 함께 반영돼야 한다. 이미 받은 access 토큰은
 * {@code AccountStanding}(Ruling 880)이 막고, 이쪽은 refresh 로 새 access 를 받는 길을 끊는다.
 */
@Component
@RequiredArgsConstructor
public class ManagerDeletedSessionListener {

    private final RefreshTokenRepository refreshTokenRepository;

    @EventListener
    public void revokeSessions(ManagerDeletedEvent event) {
        refreshTokenRepository.revokeAllValidByAccountId(event.accountId(), event.deletedAt());
    }
}
