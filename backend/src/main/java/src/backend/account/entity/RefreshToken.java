package src.backend.account.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 장기 토큰 — 로그아웃·계정 차단·비밀번호 변경 시 무효화를 판정하려면 서버가 발급분을
 * 보관해야 한다(ERD §3.1 · C-14 · API_SPEC §1.2 · §2.7 · §2.8).
 *
 * <p>{@code created_at}/{@code updated_at} 쌍이 없어 {@code BaseTimeEntity} 를 상속하지 않는다 —
 * {@code issued_at}·{@code expires_at}·{@code revoked_at} 은 전부 도메인 의미가 있는 값이라
 * 호출자가 직접 넘긴다.
 */
@Entity
@Table(name = "refresh_token")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "token_hash", length = 255, nullable = false)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false)
    private OffsetDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @Column(name = "device_label", length = 100)
    private String deviceLabel;

    private RefreshToken(Long accountId, String tokenHash, OffsetDateTime issuedAt, OffsetDateTime expiresAt,
            String deviceLabel) {
        this.accountId = accountId;
        this.tokenHash = tokenHash;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.deviceLabel = deviceLabel;
    }

    /** 로그인 성공 시점에 발급한다(AUTH-01) — 원문은 저장하지 않고 해시만 보관한다. */
    public static RefreshToken issue(Long accountId, String tokenHash, OffsetDateTime issuedAt,
            OffsetDateTime expiresAt, String deviceLabel) {
        return new RefreshToken(accountId, tokenHash, issuedAt, expiresAt, deviceLabel);
    }
}
