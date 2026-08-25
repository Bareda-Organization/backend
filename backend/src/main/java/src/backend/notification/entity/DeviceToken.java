package src.backend.notification.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import src.backend.global.common.BaseTimeEntity;

/**
 * 푸시 수신 단말 — 서버가 발송 대상 단말을 특정하는 자리이며, 토큰 갱신은 값 수정이 아니라 행 대체로
 * 처리한다(ERD §3.6). {@code account_id}·{@code device_id} UNIQUE 라 계정당 단말별로 행이 최대 1개다.
 */
@Entity
@Table(name = "device_token")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeviceToken extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "device_id", length = 100, nullable = false)
    private String deviceId;

    @Column(name = "token", columnDefinition = "text", nullable = false)
    private String token;

    @Convert(converter = DevicePlatform.Db.class)
    @Column(name = "platform", length = 10, nullable = false)
    private DevicePlatform platform;

    @Column(name = "app_version", length = 20)
    private String appVersion;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @Column(name = "last_used_at")
    private OffsetDateTime lastUsedAt;

    private DeviceToken(Long accountId, String deviceId, String token, DevicePlatform platform) {
        this.accountId = accountId;
        this.deviceId = deviceId;
        this.token = token;
        this.platform = platform;
    }

    /** 단말이 최초 등록되거나 토큰이 갱신될 때(기존 행 대체) 생성한다. */
    public static DeviceToken register(Long accountId, String deviceId, String token, DevicePlatform platform) {
        return new DeviceToken(accountId, deviceId, token, platform);
    }

    /**
     * 수동 해지(API_SPEC §2.11 {@code DELETE /me/devices/{token}}) — 행을 지우지 않고
     * {@code revoked_at} 만 채운다. 무효 토큰 정리(발송 실패 기반, 알림 발송 로직 소관)와 달리
     * 해지는 이력을 남겨야 할 사용자 조작이라 삭제 대신 마킹으로 처리한다.
     */
    public void revoke(OffsetDateTime revokedAt) {
        this.revokedAt = revokedAt;
    }
}
