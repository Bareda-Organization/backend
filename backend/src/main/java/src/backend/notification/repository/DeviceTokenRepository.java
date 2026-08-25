package src.backend.notification.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import src.backend.global.security.access.AcademyScopeExempt;
import src.backend.notification.entity.DeviceToken;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, Long> {
    /** (account_id, device_id) UNIQUE — 같은 기기 재등록 시 기존 행을 찾아 대체하는 데 쓴다. */
    @AcademyScopeExempt(reason = "§2.11 본인 단말 등록 — device_token 은 account 부모 경유라 계정이 곧 학원 범위이고, "
            + "조건을 더해도 좁혀지는 것이 부재. 호출부가 토큰의 accountId 만 넘긴다는 전제 — "
            + "요청 파라미터의 accountId 를 넘기면 이 예외가 우회로가 된다")
    Optional<DeviceToken> findByAccountIdAndDeviceId(Long accountId, String deviceId);

    /** {@code DELETE /me/devices/{token}} 의 소유권 확인 — 남의 토큰 값을 넣어도 조회되지 않는다. */
    @AcademyScopeExempt(reason = "§2.11 본인 단말 해지 — 소유권 대조가 accountId 로 이미 이뤄져 학원 조건이 판정에 개입 부재. "
            + "호출부가 토큰의 accountId 만 넘긴다는 전제 — 요청 파라미터의 accountId 를 넘기면 이 예외가 우회로가 된다")
    Optional<DeviceToken> findByAccountIdAndToken(Long accountId, String token);
}
