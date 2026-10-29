package src.backend.notification.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.security.access.AcademyScopeExempt;
import src.backend.notification.entity.DeviceToken;

/** {@code device_token}(§2.11 푸시 단말) 저장소 — 등록·해지·발송 대상 조회·무효 토큰 정리. */
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

    /** 발송 대상 — 그 계정의 해지되지 않은 전 단말(§2.11 "발송은 유효한 전 토큰에", Ruling 331). */
    @AcademyScopeExempt(reason = "푸시 발송 — 수신 계정은 이미 학원 범위로 적재된 알림 행의 recipient_account_id 다. "
            + "device_token 은 account 부모 경유라 계정이 곧 학원 범위이고, 요청 파라미터가 닿지 않는 내부 경로다")
    List<DeviceToken> findAllByAccountIdAndRevokedAtIsNull(Long accountId);

    /**
     * FCM 이 무효라고 답한 토큰을 해지한다(§2.11 무효 토큰 정리, Ruling 331) — 발송은 트랜잭션 밖이라 이 갱신이
     * 자기 트랜잭션을 연다. 이미 해지된 행은 건드리지 않는다({@link DeviceToken#revoke} 와 같은 규칙).
     */
    @AcademyScopeExempt(reason = "무효 토큰 정리 — 위 발송 대상 조회가 이미 좁혀 얻은 행 id 하나에 대한 갱신이다")
    @Transactional
    @Modifying
    @Query("UPDATE DeviceToken d SET d.revokedAt = :revokedAt WHERE d.id = :id AND d.revokedAt IS NULL")
    int revokeInvalid(@Param("id") Long id, @Param("revokedAt") OffsetDateTime revokedAt);

    /**
     * 같은 토큰 값을 다른 계정이 등록하면 앞 계정들의 유효 행을 해지한다(BR-223) — 한 물리 기기의 토큰은 한 계정에만
     * 유효하다. 로그아웃에 {@code device_id} 가 안 실리거나 세션이 만료된 채 계정을 바꾸면 앞 계정의 행이 남아, 앞 계정
     * 앞으로 적재된 알림(자녀 이름 포함)이 지금 그 폰을 쓰는 사람에게 간다.
     */
    @AcademyScopeExempt(reason = "§2.11 본인 단말 등록 — 토큰 값이 같은 다른 계정의 행을 정리하는 갱신이고 학원 조건이 좁힐 것이 부재. "
            + "호출부가 토큰의 accountId 만 넘긴다는 전제 — 요청 파라미터의 accountId 를 넘기면 이 예외가 우회로가 된다")
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE DeviceToken d SET d.revokedAt = :revokedAt "
            + "WHERE d.token = :token AND d.accountId <> :accountId AND d.revokedAt IS NULL")
    int revokeOtherAccounts(@Param("token") String token, @Param("accountId") Long accountId,
            @Param("revokedAt") OffsetDateTime revokedAt);

    /**
     * 같은 (계정, 단말) 행을 새 토큰으로 바꿔 심는다 — 없으면 만들고, 있으면 값 수정이 아니라 행 대체와 같은 결과(해지 표시 해제 · 생성 시각 갱신)로
     * 덮는다(ERD §3.4). 한 문장이라 같은 단말의 첫 등록 두 건이 겹쳐도 UNIQUE 위반(500) 없이 나중 값이 남는다. 지우고 다시 넣는 세 걸음은 두 요청이
     * 모두 "없음" 을 본 뒤 함께 넣는 창이 있다.
     */
    @AcademyScopeExempt(reason = "§2.11 본인 단말 등록 — device_token 은 account 부모 경유라 계정이 곧 학원 범위이고, 호출부가 토큰의 "
            + "accountId 만 넘긴다는 전제 — 요청 파라미터의 accountId 를 넘기면 이 예외가 우회로가 된다")
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO device_token (account_id, device_id, token, platform, app_version, revoked_at, created_at, updated_at)
            VALUES (:accountId, :deviceId, :token, :platform, :appVersion, NULL, :now, :now)
            ON CONFLICT (account_id, device_id) DO UPDATE
               SET token = EXCLUDED.token, platform = EXCLUDED.platform, app_version = EXCLUDED.app_version,
                   revoked_at = NULL, created_at = EXCLUDED.created_at, updated_at = EXCLUDED.updated_at
            """, nativeQuery = true)
    int upsert(@Param("accountId") Long accountId, @Param("deviceId") String deviceId, @Param("token") String token,
            @Param("platform") String platform, @Param("appVersion") String appVersion, @Param("now") OffsetDateTime now);

    /** 계정의 푸시 단말을 전량 지운다(익명화 파기 Ruling 480 ②·520 · 매니저 삭제 Ruling 891) — 해지 표시가 아니라 행을 지운다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @AcademyScopeExempt(reason = "보존 정리 배치(Ruling 480 ②) · 매니저 삭제(Ruling 891) — 계정 id 는 학생 파기 처리 또는 "
            + "삭제 이벤트가 고른 값이고, 요청 파라미터가 닿지 않는 내부 경로")
    @Query("DELETE FROM DeviceToken d WHERE d.accountId IN :accountIds")
    int deleteAllByAccountIds(@Param("accountIds") Collection<Long> accountIds);
}
