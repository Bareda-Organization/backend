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

import src.backend.global.common.BaseTimeEntity;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/**
 * 로그인 계정 — 전 인원(학생·학부모·기사·동승자·관계자·플랫폼 관리자)이 form 가입으로 만드는
 * 단일 인증 주체다(ERD §3.1 · C-01 · AUTH-01~09).
 *
 * <p>{@code academy_id} 는 {@code role='system_admin'} 일 때만 NULL 이 허용된다
 * (DB CHECK {@code ck_account_academy_scope}) — 이 판정은 스키마가 강제하며 엔티티는 값을 그대로
 * 옮길 뿐 검증하지 않는다.
 */
@Entity
@Table(name = "account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "academy_id")
    private Long academyId;

    @Column(name = "login_id", length = 50, nullable = false)
    private String loginId;

    @Column(name = "password_hash", length = 255, nullable = false)
    private String passwordHash;

    @Column(name = "name", length = 50, nullable = false)
    private String name;

    @Column(name = "phone", length = 30, nullable = false)
    private String phone;

    @Column(name = "email", length = 120)
    private String email;

    @Convert(converter = Role.Db.class)
    @Column(name = "role", length = 20, nullable = false)
    private Role role;

    @Convert(converter = AccountStatus.Db.class)
    @Column(name = "status", length = 10, nullable = false)
    private AccountStatus status;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "blocked_at")
    private OffsetDateTime blockedAt;

    @Column(name = "block_reason", length = 100)
    private String blockReason;

    @Column(name = "unblocked_by")
    private Long unblockedBy;

    @Column(name = "unblocked_at")
    private OffsetDateTime unblockedAt;

    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;

    private Account(Long academyId, String loginId, String passwordHash, String name, String phone,
            String email, Role role) {
        this.academyId = academyId;
        this.loginId = loginId;
        this.passwordHash = passwordHash;
        this.name = name;
        this.phone = phone;
        this.email = email;
        this.role = role;
        this.status = AccountStatus.PENDING;
        this.failedAttempts = 0;
    }

    /**
     * 회원가입 form 제출 시점에 생성한다(AUTH-01) — 승인 대기 상태로 시작한다.
     *
     * <p>플랫폼 관리자({@code system_admin}) 계정처럼 가입 절차 없이 내부 발급되는 경로는
     * Phase 1 범위 밖이다 — 소유 Phase 가 별도 팩토리로 분화시킨다.
     */
    public static Account forSignup(Long academyId, String loginId, String passwordHash, String name,
            String phone, String email, Role role) {
        return new Account(academyId, loginId, passwordHash, name, phone, email, role);
    }

    /**
     * {@code blocked} 계정의 로그인을 막는다(API_SPEC §1.4) — 그 외 상태(pending·rejected·active)는
     * 로그인 자체는 허용하므로 여기서 걸리지 않는다. 통과하면 아무 것도 하지 않고, 아니면
     * {@link BusinessException}({@code AUTH_ACCOUNT_BLOCKED}, 401 이 아닌 403).
     *
     * <p>로그인 서비스(Task 4)가 비밀번호 대조보다 먼저 호출한다 — 자격 오류(401)로 응답하면
     * 사용자가 비밀번호가 틀린 줄 알고 재시도하고, 그 재시도가 실패 카운터를 다시 올리게 된다.
     */
    public void assertNotBlocked() {
        if (status == AccountStatus.BLOCKED) {
            throw new BusinessException(ErrorCode.AUTH_ACCOUNT_BLOCKED);
        }
    }

    /**
     * 가입 거절 후 재신청한다(AUTH-03·API_SPEC §2.4) — {@code rejected} 상태에서만 허용되고,
     * 재신청 시점에 학원을 다시 선택할 수 있어 {@code academyId} 도 함께 갱신한다.
     *
     * <p>{@code rejected} 가 아니면 {@link BusinessException}({@code REAPPLY_NOT_ALLOWED}) —
     * pending·active·blocked 계정이 이 경로로 상태를 되돌리는 것을 막는다.
     */
    public void reapply(Long newAcademyId) {
        if (status != AccountStatus.REJECTED) {
            throw new BusinessException(ErrorCode.REAPPLY_NOT_ALLOWED);
        }
        this.academyId = newAcademyId;
        this.status = AccountStatus.PENDING;
    }
}
