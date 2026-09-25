package src.backend.account.command;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.account.repository.AccountRepository;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.manager.event.ManagerRoleChangedEvent;

/**
 * 매니저 역할 변경을 계정 역할에 옮긴다(MGR-03 · API_SPEC §5.13 "이 값이 앱 권한을 결정", BR-022).
 *
 * <p>{@code @EventListener}(동기)인 이유 — 매니저 역할을 바꾼 트랜잭션 안에서 함께 커밋돼야 한다. 커밋 후
 * 처리로 두면 이 쓰기가 실패했을 때 매니저는 동승자인데 계정은 기사로 남는다. 새 역할은 다음 토큰 재발급
 * ({@code RefreshCommandService} 가 계정을 다시 읽는다)부터 실린다.
 */
@Component
@RequiredArgsConstructor
public class ManagerRoleAccountSyncListener {

    private final AccountRepository accountRepository;

    /** 연결된 계정의 역할을 매니저 역할에 맞춘다. */
    @EventListener
    public void syncRole(ManagerRoleChangedEvent event) {
        accountRepository.findById(event.accountId())
                .ifPresent(account -> account.changeRole(event.role() == ManagerRole.DRIVER ? Role.DRIVER : Role.ESCORT));
    }
}
