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
public class Account extends AccountState {

    /** 익명화한 계정의 비밀번호 해시 — bcrypt 형식이 아니라 어떤 입력과도 맞지 않는다. */
    public static final String UNUSABLE_PASSWORD_HASH = "!";

    private static final String ANONYMIZED_LOGIN_ID_PREFIX = "withdrawn-";

    /** {@code phone} 은 NOT NULL 이라 지우지 못한다 — 형식만 갖춘 빈 번호로 덮는다. */
    private static final String ANONYMIZED_PHONE = "000-0000-0000";

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
     * 초기 구현 범위 밖이다 — 소유 기능이 별도 팩토리로 분화시킨다.
     */
    public static Account forSignup(Long academyId, String loginId, String passwordHash, String name,
            String phone, String email, Role role) {
        return new Account(academyId, loginId, passwordHash, name, phone, email, role);
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
     * {@code blocked} 계정의 로그인을 막는다(API_SPEC §1.4) — 그 외 상태(pending·rejected·active)는
     * 로그인 자체는 허용하므로 여기서 걸리지 않는다. 통과하면 아무 것도 하지 않고, 아니면
     * {@link BusinessException}({@code AUTH_ACCOUNT_BLOCKED}, 401 이 아닌 403).
     *
     * <p>로그인 서비스가 비밀번호 대조보다 먼저 호출한다 — 자격 오류(401)로 응답하면
     * 사용자가 비밀번호가 틀린 줄 알고 재시도하고, 그 재시도가 실패 카운터를 다시 올리게 된다.
     */
    public void assertNotBlocked() {
        if (status == AccountStatus.BLOCKED) {
            throw new BusinessException(ErrorCode.AUTH_ACCOUNT_BLOCKED);
        }
    }

    /**
     * 로그인 성공 시 실패 카운터를 초기화한다(API_SPEC §2.5) — 이게 없으면 한 번 실패한 계정이
     * 이후 계속 성공해도 카운터가 상한에 조금씩 가까워지다 결국 무고하게 차단된다.
     */
    public void recordLoginSuccess(OffsetDateTime now) {
        this.failedAttempts = 0;
        this.lastLoginAt = now;
    }

    /**
     * 매니저 계정의 역할을 바꾼다(MGR-03 · API_SPEC §5.13) — 연결된 {@code manager.role} 이 바뀔 때만
     * 부른다. 이 값이 토큰 {@code role} 클레임이라, 매니저 역할만 바꾸면 앱 권한이 그대로 남는다.
     */
    public void changeRole(Role newRole) {
        this.role = newRole;
    }

    /**
     * 본인이 비밀번호를 바꾼다(API_SPEC §2.8) — 새 해시는 호출자(PasswordEncoder)가 만들어 넘기고, 강제 변경 표식을
     * 내린다. 표식을 내리는 자리가 여기 하나뿐이라 임시 비밀번호를 주는 경로(관리자 초기화 · 문자 복구 §2.9,
     * Ruling 785)가 이 메서드를 부르면 표식이 바로 꺼진다 — 그 경로는 {@link #issueTemporaryPassword} 를 쓴다.
     */
    public void changePassword(String newPasswordHash) {
        this.passwordHash = newPasswordHash;
        this.mustChangePassword = false;
    }

    /**
     * 임시 비밀번호를 준다 — 관리자 초기화(API_SPEC §5.22·§6.7, Ruling 540)와 문자 복구(§2.9, Ruling 785)가 부른다.
     * 임시 값은 관리자가 알거나 문자함에 평문으로 남으므로 표식을 켜 본인이 바꿀 때까지 다른 API 를 막는다.
     * 새 해시는 호출자(PasswordEncoder)가 만들어 넘긴다.
     */
    public void issueTemporaryPassword(String temporaryPasswordHash) {
        this.passwordHash = temporaryPasswordHash;
        this.mustChangePassword = true;
    }

    /**
     * 퇴원 90일이 지난 학생의 앱 계정을 익명화하고 로그인 불가로 만든다(Ruling 480 ②·520) — 계정 행은 남긴다.
     * 감사 기록·알림 기록이 계정 id 로 이 행을 가리키고, 물리 삭제 경로가 부재하다(ERD §7.1).
     *
     * <p>아이디는 {@code UNIQUE} 라 계정 id 로 만든 값으로 바꾸고, 비밀번호 해시는 어떤 입력과도 맞지 않는 값
     * ({@link #UNUSABLE_PASSWORD_HASH})으로 덮는다. 상태는 {@code blocked} 로 옮기되 차단 직전 상태를 남겨
     * {@code ck_account_status_before_block_pair} 를 지킨다 — 이미 차단된 계정은 상태·직전 상태를 그대로 두고 차단 사유만 파기 사유로 덮는다(차단 목록에서 빠지고 해제할 수 없게).
     */
    public void anonymizeForRetention(String anonymizedName, OffsetDateTime at) {
        this.loginId = ANONYMIZED_LOGIN_ID_PREFIX + id;
        this.passwordHash = UNUSABLE_PASSWORD_HASH;
        this.name = anonymizedName;
        this.phone = ANONYMIZED_PHONE;
        this.email = null;
        blockForRetention(at);
    }

    /**
     * 관계자 계정의 이름·연락처·이메일을 고친다(ACAD-06 · API_SPEC §6.7) — {@code null} 인 항목은 그대로 둔다.
     *
     * <p>{@code loginId}·{@code role}·{@code academyId} 를 인자로 받지 않는 것이 "그 셋은 수정 대상 밖"
     * 을 강제하는 방식이다({@code Academy.update} 가 {@code code} 를 받지 않는 것과 같은 형태) —
     * 요청 본문에 실려 와도 이 메서드까지 닿을 경로가 부재하다.
     */
    public void changeProfile(String newName, String newPhone, String newEmail) {
        this.name = newName == null ? this.name : newName;
        this.phone = newPhone == null ? this.phone : newPhone;
        this.email = newEmail == null ? this.email : newEmail;
    }
}
