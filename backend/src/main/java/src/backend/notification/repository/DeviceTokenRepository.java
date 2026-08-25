package src.backend.notification.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.notification.entity.DeviceToken;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, Long> {
    /** (account_id, device_id) UNIQUE — 같은 기기 재등록 시 기존 행을 찾아 대체하는 데 쓴다. */
    Optional<DeviceToken> findByAccountIdAndDeviceId(Long accountId, String deviceId);

    /** {@code DELETE /me/devices/{token}} 의 소유권 확인 — 남의 토큰 값을 넣어도 조회되지 않는다. */
    Optional<DeviceToken> findByAccountIdAndToken(Long accountId, String token);
}
