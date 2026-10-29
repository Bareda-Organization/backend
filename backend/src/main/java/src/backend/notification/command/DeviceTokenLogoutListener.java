package src.backend.notification.command;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.account.event.AccountLoggedOutEvent;
import src.backend.notification.repository.DeviceTokenRepository;

/**
 * 로그아웃한 기기의 푸시 단말 토큰을 해지한다(API_SPEC §2.11 "로그아웃 시 해당 기기 토큰 자동 해지", Ruling 331).
 *
 * <p><b>커밋 뒤 리스너가 아니라 동기 {@code @EventListener} 인 것은 의도다</b>(CODE_CONVENTIONS §6.1 의 "커밋 뒤 리스너는 WARN 으로 남기고 삼킨다" 와 갈리는
 * 자리). 해지가 로그아웃 트랜잭션 안에서 함께 반영돼야 로그아웃은 됐는데 그 기기로 자녀 이름이 담긴 알림이 계속 가는 중간 상태가 남지 않는다 — 해지가 실패하면
 * 삼키지 않고 로그아웃도 함께 되돌려 사용자가 다시 시도하게 한다. 조회가 이벤트의 계정으로 좁혀져 남의 기기는 해지할 수 없다.
 */
@Component
@RequiredArgsConstructor
public class DeviceTokenLogoutListener {

    private final DeviceTokenRepository deviceTokenRepository;

    /** 로그아웃한 계정의 그 단말 토큰에 해지 시각을 남긴다 — 단말 id 가 없는 로그아웃이면 대상 행이 없어 아무것도 하지 않는다. */
    @EventListener
    public void revokeDevice(AccountLoggedOutEvent event) {
        deviceTokenRepository.findByAccountIdAndDeviceId(event.accountId(), event.deviceId())
                .ifPresent(deviceToken -> deviceToken.revoke(event.loggedOutAt()));
    }
}
