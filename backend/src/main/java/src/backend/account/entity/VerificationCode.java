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

    /**
     * 인증 코드 대조 상한 — 도달하면 옳은 코드를 넣어도 더는 통과하지 않는다.
     *
     * <p>API_SPEC §2.9 는 상한을 규정하지 않으나 {@code verification_code.attempt_count} 컬럼의 존재
     * 자체가 상한을 전제한 스키마다(리뷰 라운드 1 C1). 값을 {@code Account.MAX_FAILED_ATTEMPTS}(C-11)
     * 와 같은 5 로 두는 이유는, 같은 시스템 안에서 "몇 번 틀리면 막느냐" 의 답이 둘로 갈리려면 그
     * 차이를 설명할 근거가 있어야 하는데 그런 근거가 부재하기 때문이다. 상수를 공유하지 않고 값만
     * 맞춘 것은 대상이 다르기 때문이다 — 저쪽은 계정을 차단하고 이쪽은 코드 1건을 소진시킨다.
     */
    private static final int MAX_ATTEMPTS = 5;

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

    /**
     * 제출된 코드가 유효한지 판정한다(API_SPEC §2.9) — 이미 소비됐거나, 만료됐거나, 값이 다르거나,
     * 대조 시도가 {@link #MAX_ATTEMPTS} 에 도달했으면 {@code false} 다. 상한 도달분과 불일치를 같은
     * 값으로 돌려주는 것은 의도다 — 호출부가 둘을 갈라 응답하면 "이 전화번호는 코드를 발급받은
     * 적이 있다" 를 미인증 응답으로 알려 주게 된다(리뷰 라운드 1 C1).
     *
     * <p><b>실패를 예외가 아니라 반환값으로 돌려주는 이유</b> — 이 메서드가 예외를 던지면 호출부의
     * {@code @Transactional} 이 롤백돼 방금 올린 {@code attempt_count} 가 DB 에 남지 않는다. 누적이
     * 사라지면 상한은 있으나 마나이므로(공격자가 무제한 대조 가능), 판정을 값으로 돌려 같은
     * 트랜잭션이 정상 커밋되게 한다(리뷰 라운드 1 C1-③).
     *
     * <p>상한에 이미 도달한 뒤의 호출은 카운터를 더 올리지 않는다 — CHECK 제약은 없으나 상한을
     * 넘긴 값은 아무 의미도 더하지 않고, 로그로 볼 때 "5에서 멈췄다" 가 상한 동작의 근거가 된다.
     *
     * @return 코드가 유효하면 {@code true}
     */
    public boolean verify(String inputCode, OffsetDateTime now) {
        if (attemptCount >= MAX_ATTEMPTS) {
            return false;
        }
        this.attemptCount++;
        return consumedAt == null && !now.isAfter(expiresAt) && code.equals(inputCode);
    }

    /** 검증에 성공해 이 코드를 소비 처리한다 — 같은 코드로 두 번 복구를 진행하지 못하게 한다. */
    public void consume(OffsetDateTime now) {
        this.consumedAt = now;
    }
}
