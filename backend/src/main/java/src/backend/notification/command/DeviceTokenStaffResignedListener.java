package src.backend.notification.command;

import java.util.List;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.academy.event.StaffResignedEvent;
import src.backend.notification.repository.DeviceTokenRepository;

/**
 * 퇴사한 관계자 계정의 푸시 단말 행을 지운다(R56 Ruling 894 ②) — 단말 등록 API 가 역할을 가리지 않아 행이 남을 수 있고, 남으면 퇴사자의
 * 폰으로 알림이 계속 간다.
 *
 * <p>평범한 {@code @EventListener} 라 퇴사 트랜잭션 안에서 함께 반영된다({@link DeviceTokenManagerDeletedListener} 와 같은 이유).
 */
@Component
@RequiredArgsConstructor
public class DeviceTokenStaffResignedListener {

    private final DeviceTokenRepository deviceTokenRepository;

    @EventListener
    public void deleteDevices(StaffResignedEvent event) {
        deviceTokenRepository.deleteAllByAccountIds(List.of(event.accountId()));
    }
}
