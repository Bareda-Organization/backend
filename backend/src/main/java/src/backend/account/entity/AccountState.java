package src.backend.account.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.MappedSuperclass;

import lombok.Getter;

import src.backend.global.common.BaseTimeEntity;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/**
 * {@link Account} 의 상태 · 로그인 보안 · 차단 이력 컬럼과 그 전이 규칙 — 가입 승인 상태, 로그인 실패 카운터(C-11), 차단과 해제 이력,
 * 마지막 로그인 시각이다. 테이블은 {@code account} 하나이고 컬럼 이름 · 매핑은 그대로이며, 신원 컬럼(아이디 · 이름 · 연락처 · 역할)은
 * {@link Account} 가 갖는다. 필드는 같은 패키지의 {@link Account} 만 바꿀 수 있다.
 */
@MappedSuperclass
@Getter
public abstract class AccountState extends BaseTimeEntity {

    /**
     * 로그인 실패 상한(C-11) — 도달하면 계정을 차단한다. {@code ck_account_failed_attempts} CHECK(0~5)와
     * 같은 값이다.
     *
     * <p>{@code public} 인 이유는 이 상한을 검증하는 테스트가 회차 수를 여기서 끌어 써야 하기
     * 때문이다 — 테스트에 5 를 박아 두면 상한을 바꿨을 때 테스트가 옛 값을 조용히 요구한다.
     */
    public static final int MAX_FAILED_ATTEMPTS = 5;

    /** 퇴원 보존 기간 익명화가 {@code blocked} 로 옮기며 남기는 차단 사유 — 로그인 실패 차단과 가르는 표지다. */
    public static final String RETENTION_BLOCK_REASON = "퇴원 90일 경과 개인정보 파기";

    /**
     * 첫 실패 뒤 남는 시도 횟수 — 미등록 {@code login_id} 의 실패 응답이 실어야 하는 값이다
     * ({@code INVALID_CREDENTIALS.details.remaining_attempts}, API_SPEC §2.5).
     *
     * <p>미등록에는 누적할 카운터가 부재해 {@link #recordLoginFailure} 를 부를 수 없다. 그런데 그 응답의
     * 숫자가 존재 계정의 첫 실패와 다르면, 공격자는 후보 아이디에 틀린 비밀번호를 <b>한 번</b> 보내고
     * 5 인지 4 인지만 보고 계정 존재를 판정한다(계정 열거).
     *
     * <p>이름을 붙여 한 곳에서만 정의하는 이유는, 두 자리가 각자 {@code MAX_FAILED_ATTEMPTS - 1} 을
     * 계산하면 C-11 상한이 바뀔 때 한쪽만 따라가고 그 순간 열거가 되살아나기 때문이다.
     */
    public static final int REMAINING_AFTER_FIRST_FAILURE = MAX_FAILED_ATTEMPTS - 1;

    @Convert(converter = AccountStatus.Db.class)
    @Column(name = "status", length = 10, nullable = false)
    AccountStatus status;

    @Column(name = "failed_attempts", nullable = false)
    int failedAttempts;

    /**
     * 임시 비밀번호 강제 변경 표식(Ruling 540) — 관리자가 비밀번호를 초기화하면 켜지고, 본인이 바꾸면 꺼진다.
     * 켜져 있는 동안 계정 상태 게이트가 비밀번호 변경 등 3개 외의 API 를 막는다.
     */
    @Column(name = "must_change_password", nullable = false)
    boolean mustChangePassword;

    /**
     * 차단 직전 상태(Ruling 328) — {@code blocked} 일 때만 값이 있다(CHECK {@code ck_account_status_before_block}).
     * 해제가 이 값으로 돌아가야 승인 대기·거절 계정이 해제만으로 {@code active} 가 되지 않는다.
     */
    @Convert(converter = AccountStatus.Db.class)
    @Column(name = "status_before_block", length = 10)
    AccountStatus statusBeforeBlock;

    @Column(name = "blocked_at")
    OffsetDateTime blockedAt;

    @Column(name = "block_reason", length = 100)
    String blockReason;

    @Column(name = "unblocked_by")
    Long unblockedBy;

    @Column(name = "unblocked_at")
    OffsetDateTime unblockedAt;

    @Column(name = "last_login_at")
    OffsetDateTime lastLoginAt;

    /**
     * 가입 요청이 수락되어 계정을 활성화한다(AUTH-10 · API_SPEC §5.2·§6.5).
     *
     * <p>요청 행 쪽 확인({@code SignupRequest#assertPending})과 겹쳐 보이지만 보는 대상이 다르다 —
     * 그쪽은 "이 요청이 처리됐나", 이쪽은 "이 계정이 지금 승인을 받을 수 있는 상태인가" 다. 둘이
     * 갈리는 실제 경로가 있다: {@code pending} 계정도 로그인은 되므로(§1.4) 승인을 기다리는 동안
     * 실패 5회로 {@code blocked} 가 될 수 있고, 그때 요청은 여전히 대기 중이다.
     */
    public void approveSignup() {
        assertAwaitingDecision();
        this.status = AccountStatus.ACTIVE;
    }

    /** 가입 요청이 거절되어 계정을 {@code rejected} 로 만든다(AUTH-10 · §5.2·§6.5) — 재신청은 이 상태에서만 열린다. */
    public void rejectSignup() {
        assertAwaitingDecision();
        this.status = AccountStatus.REJECTED;
    }

    /**
     * 승인·거절을 받을 수 있는 상태인지 본다 — 두 경우를 <b>다른 코드로</b> 가른다.
     *
     * <p>{@code blocked} 는 {@code SIGNUP_TARGET_BLOCKED}(409)다. 통과시키면 승인이 차단을 조용히
     * 풀어, 로그인 실패 5회로 잠긴 계정이 관계자 승인 한 번으로 되살아난다 — 해제 권한은 메인
     * 관리자에게만 있다(AUTH-06 · C-11). {@code APPROVAL_ALREADY_DECIDED} 로 뭉뚱그리면 화면에
     * "이미 처리된 요청" 이 뜨는데 그 건은 큐에 그대로 남아 있어, 관계자가 원인을 찾을 수단이 부재하다.
     *
     * <p>{@code AUTH_ACCOUNT_BLOCKED} 를 쓰지 않는 이유(Ruling 147) — 그 코드는 <b>요청 주체 자신</b>이
     * 차단된 경우를 가리킨다(§1.11). 여기서 차단된 것은 <b>승인 대상</b>이고 요청 주체는 정상 권한을
     * 가진 관계자·메인 관리자다. 재사용하면 승인 화면에 "차단된 계정입니다. 관리자에게 문의하세요" 가
     * 떠 승인자가 자신이 차단된 것으로 오해한다. 403 이 아니라 409 인 것은 막는 것이 권한이 아니라
     * <b>대상 자원의 상태</b>이기 때문이며, 바로 아래 {@code APPROVAL_ALREADY_DECIDED} 와 같은 형태다.
     *
     * <p>그 밖의 비-{@code pending}({@code active}·{@code rejected})은 요청 행과 계정이 어긋난
     * 상태이므로 {@code APPROVAL_ALREADY_DECIDED} 다.
     */
    private void assertAwaitingDecision() {
        if (status == AccountStatus.BLOCKED) {
            throw new BusinessException(ErrorCode.SIGNUP_TARGET_BLOCKED);
        }
        if (status != AccountStatus.PENDING) {
            throw new BusinessException(ErrorCode.APPROVAL_ALREADY_DECIDED);
        }
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
            this.statusBeforeBlock = this.status;
            this.status = AccountStatus.BLOCKED;
            this.blockedAt = now;
            this.blockReason = "로그인 실패 " + MAX_FAILED_ATTEMPTS + "회 누적(C-11)";
            return 0;
        }
        return MAX_FAILED_ATTEMPTS - this.failedAttempts;
    }

    /**
     * 메인 관리자가 로그인 차단을 해제한다(AUTH-06 · API_SPEC §6.12) — {@code blocked} 가 아니거나 퇴원 보존 익명화로 막힌 계정이면
     * {@link BusinessException}({@code ACCOUNT_NOT_BLOCKED}, 409). 익명화 계정을 풀면 지워 둔 개인정보 자리의 계정이 되살아난다.
     *
     * <p><b>{@code failedAttempts} 초기화가 이 메서드의 절반이다.</b> 상태만 되돌리고
     * 카운터를 두면 상한을 채운 값이 남아 <b>다음 1회 실패로 즉시 재차단</b>된다. 그런데 해제 직후의
     * 로그인은 성공하고, 성공하는 순간 {@link #recordLoginSuccess} 가 카운터를 0 으로 돌려놓아
     * "해제 후 로그인 200" 만 보는 단언으로는 이 결함을 관측할 수단이 부재하다.
     *
     * <p>{@code blockedAt}·{@code blockReason} 은 지우지 않는다 — 마지막 차단이 언제 왜 걸렸는지는
     * 해제 뒤에도 남아야 하는 이력이고, {@code unblockedAt} 과 짝을 이뤄 한 사건의 시작과 끝이 된다.
     *
     * <p><b>돌아가는 상태는 {@code active} 고정이 아니라 차단 직전 상태다</b>(Ruling 328). 차단 사유는 로그인
     * 실패 5회이고 가입 승인과 무관하다 — {@code active} 고정이면 승인 대기 중 차단된 관계자 계정이 해제만으로
     * 가입 승인을 건너뛰고 학원 전체 개인정보 권한을 얻는다.
     *
     * @param actorAccountId 해제를 실행한 메인 관리자 계정({@code unblocked_by})
     */
    public void unblock(Long actorAccountId, OffsetDateTime now) {
        if (status != AccountStatus.BLOCKED || RETENTION_BLOCK_REASON.equals(blockReason)) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_BLOCKED);
        }
        this.status = statusBeforeBlock;
        this.statusBeforeBlock = null;
        this.failedAttempts = 0;
        this.unblockedBy = actorAccountId;
        this.unblockedAt = now;
    }

    /**
     * 퇴원 보존 익명화가 계정을 {@code blocked} 로 옮긴다 — 차단 직전 상태를 남겨 {@code ck_account_status_before_block_pair} 를 지키고,
     * 이미 차단된 계정은 상태 · 직전 상태 · 차단 사유를 그대로 둔다.
     */
    void blockForRetention(OffsetDateTime at) {
        if (status != AccountStatus.BLOCKED) {
            this.statusBeforeBlock = status;
            this.status = AccountStatus.BLOCKED;
            this.blockedAt = at;
            this.blockReason = RETENTION_BLOCK_REASON;
        }
    }
}
