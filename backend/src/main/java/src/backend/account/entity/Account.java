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

    /**
     * 로그인 실패 상한(C-11) — 도달하면 계정을 차단한다. {@code ck_account_failed_attempts} CHECK(0~5)와
     * 같은 값이다.
     *
     * <p>{@code public} 인 이유는 이 상한을 검증하는 테스트가 회차 수를 여기서 끌어 써야 하기
     * 때문이다 — 테스트에 5 를 박아 두면 상한을 바꿨을 때 테스트가 옛 값을 조용히 요구한다.
     */
    public static final int MAX_FAILED_ATTEMPTS = 5;

    /**
     * 첫 실패 뒤 남는 시도 횟수 — 미등록 {@code login_id} 의 실패 응답이 실어야 하는 값이다
     * ({@code INVALID_CREDENTIALS.details.remaining_attempts}, API_SPEC §2.5).
     *
     * <p>미등록에는 누적할 카운터가 부재해 {@link #recordLoginFailure} 를 부를 수 없다. 그런데 그 응답의
     * 숫자가 존재 계정의 첫 실패와 다르면, 공격자는 후보 아이디에 틀린 비밀번호를 <b>한 번</b> 보내고
     * 5 인지 4 인지만 보고 계정 존재를 판정한다(계정 열거, 리뷰 라운드 2 I-1).
     *
     * <p>이름을 붙여 한 곳에서만 정의하는 이유는, 두 자리가 각자 {@code MAX_FAILED_ATTEMPTS - 1} 을
     * 계산하면 C-11 상한이 바뀔 때 한쪽만 따라가고 그 순간 열거가 되살아나기 때문이다.
     */
    public static final int REMAINING_AFTER_FIRST_FAILURE = MAX_FAILED_ATTEMPTS - 1;

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

    /**
     * 로그인 실패를 1회 누적한다(API_SPEC §2.5 · C-11) — 누적치가 상한에 도달하면 즉시 {@code blocked}
     * 로 전이한다. 도달 전까지는 카운터만 올리고 상태는 바꾸지 않는다.
     *
     * <p>첫 실패({@code failedAttempts == 1})의 반환값은 {@link #REMAINING_AFTER_FIRST_FAILURE} 와
     * 같다 — 미등록 {@code login_id} 의 응답이 그 상수를 실어 두 응답이 갈리지 않게 한다. 이 등식이
     * 깨지면 숫자 하나로 계정 존재가 드러나므로 {@code AuthControllerTest} 가 두 응답을 대조한다.
     *
     * @return 이 실패 이후 남은 시도 횟수({@code INVALID_CREDENTIALS.details.remaining_attempts}) — 상한 도달 시 0
     */
    public int recordLoginFailure(OffsetDateTime now) {
        this.failedAttempts++;
        if (this.failedAttempts >= MAX_FAILED_ATTEMPTS) {
            this.status = AccountStatus.BLOCKED;
            this.blockedAt = now;
            this.blockReason = "로그인 실패 " + MAX_FAILED_ATTEMPTS + "회 누적(C-11)";
            return 0;
        }
        return MAX_FAILED_ATTEMPTS - this.failedAttempts;
    }

    /**
     * 로그인 성공 시 실패 카운터를 초기화한다(API_SPEC §2.5) — 이게 없으면 한 번 실패한 계정이
     * 이후 계속 성공해도 카운터가 상한에 조금씩 가까워지다 결국 무고하게 차단된다.
     */
    public void recordLoginSuccess(OffsetDateTime now) {
        this.failedAttempts = 0;
        this.lastLoginAt = now;
    }

    /** 비밀번호를 변경한다(API_SPEC §2.8·§2.9) — 새 해시는 호출자(PasswordEncoder)가 만들어 넘긴다. */
    public void changePassword(String newPasswordHash) {
        this.passwordHash = newPasswordHash;
    }
}
