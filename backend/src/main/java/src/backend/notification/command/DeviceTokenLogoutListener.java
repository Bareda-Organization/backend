package src.backend.notification.command;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.account.event.AccountLoggedOutEvent;
import src.backend.notification.repository.DeviceTokenRepository;

/**
 * 로그아웃한 기기의 푸시 단말 토큰을 해지한다(API_SPEC §2.11 "로그아웃 시 해당 기기 토큰 자동 해지", Ruling 331).
 *
 * <p>평범한 {@code @EventListener} 다 — 로그아웃 트랜잭션 안에서 함께 반영돼, 로그아웃은 됐는데 그 기기로
 * 알림이 계속 가는 중간 상태가 남지 않는다. 조회가 이벤트의 계정으로 좁혀져 남의 기기는 해지할 수 없다.
 */
@Component
@RequiredArgsConstructor
public class DeviceTokenLogoutListener {

    private final DeviceTokenRepository deviceTokenRepository;

    @EventListener
    public void revokeDevice(AccountLoggedOutEvent event) {
        deviceTokenRepository.findByAccountIdAndDeviceId(event.accountId(), event.deviceId())
                .ifPresent(deviceToken -> deviceToken.revoke(event.loggedOutAt()));
    }
}
