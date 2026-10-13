package src.backend.notification.command;

import java.util.List;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.manager.event.ManagerDeletedEvent;
import src.backend.notification.repository.DeviceTokenRepository;

/**
 * 삭제된 매니저 계정의 푸시 단말 행을 지운다(Ruling 891) — 남으면 삭제된 사람의 폰으로 알림이 계속 간다.
 *
 * <p>평범한 {@code @EventListener} 라 삭제 트랜잭션 안에서 함께 반영된다({@link DeviceTokenLogoutListener} 와 같은 이유).
 */
@Component
@RequiredArgsConstructor
public class DeviceTokenManagerDeletedListener {

    private final DeviceTokenRepository deviceTokenRepository;

    @EventListener
    public void deleteDevices(ManagerDeletedEvent event) {
        deviceTokenRepository.deleteAllByAccountIds(List.of(event.accountId()));
    }
}
