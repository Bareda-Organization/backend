package src.backend.account.entity;

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

/**
 * 아이디·비밀번호 복구 인증 코드 — {@code POST /auth/recover} 가 만료·불일치를 판정하려면
 * 서버가 발급분을 보관해야 한다(ERD §3.2 · AUTH-08 · API_SPEC §2.9 · §8.1).
 *
 * <p>{@code purpose} 값은 {@code login_id} · {@code password} 다(재료 문서의 {@code recover_id}/
 * {@code recover_password} 는 낡은 값 — Ruling 26).
 *
 * <p>{@code updated_at} 이 없어({@code created_at} 만 존재) {@code BaseTimeEntity} 를 상속하지
 * 않는다. DB 에 {@code DEFAULT now()} 가 있지만 감사 메커니즘은 {@code BaseTimeEntity} 전용이라
 * 이 필드는 호출자가 값을 직접 넘긴다.
 */
@Entity
@Table(name = "verification_code")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VerificationCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "phone", length = 20, nullable = false)
    private String phone;

    @Column(name = "code", length = 10, nullable = false)
    private String code;

    @Convert(converter = VerificationPurpose.Db.class)
    @Column(name = "purpose", length = 20, nullable = false)
    private VerificationPurpose purpose;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "consumed_at")
    private OffsetDateTime consumedAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    private VerificationCode(String phone, String code, VerificationPurpose purpose, OffsetDateTime expiresAt,
            OffsetDateTime createdAt) {
        this.phone = phone;
        this.code = code;
        this.purpose = purpose;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
        this.attemptCount = 0;
    }

    /** SMS 발송 시점에 생성한다(AUTH-08) — 대조 시도 횟수는 0에서 시작한다. */
    public static VerificationCode issue(String phone, String code, VerificationPurpose purpose,
            OffsetDateTime expiresAt, OffsetDateTime createdAt) {
        return new VerificationCode(phone, code, purpose, expiresAt, createdAt);
    }
}
