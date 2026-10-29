package src.backend.audit.entity;

import java.time.OffsetDateTime;
import java.util.Map;

import src.backend.account.entity.LoginIdFormat;

/**
 * 로그인 · 계정 관리 감사 행의 팩토리(SYS-02 · AUTH-06 · API_SPEC §5.22) — {@link AuditLog} 의 같은 이름 팩토리가 이 클래스로 위임한다. 행은
 * 행위자 스냅샷(학원 · 계정 · 로그인 아이디 · IP)과 시각을 값으로 받는 불변 기록이다.
 */
final class AuditLoginRows {

    /** {@code target_type} 이 계정을 가리킬 때의 값 — 조회 Phase 가 이 문자열로 대상 종류를 가른다. */
    private static final String TARGET_TYPE_ACCOUNT = "account";

    /**
     * 메인 관리자가 로그인 차단을 해제한 사실을 남긴다(AUTH-06 · API_SPEC §6.12).
     *
     * <p>{@code category} 가 {@link AuditCategory#LOGIN} 인 이유는 ERD §3.4 이 {@code block}·
     * {@code unblock} 을 로그인·차단 이력 쪽 값으로 정의하고, API_SPEC §6.13 의 접속 이력 조회가 이 두 값을
     * {@code block_event} 로 투영하기 때문이다 — {@code data_access} 로 넣으면 그 화면에서 사라진다.
     *
     * <p>{@code blockEvent} 는 false 로 둔다. 그 컬럼은 "이 <b>시도</b>가 차단을 유발했는지" 를 뜻하며
     * (ERD §3.4), 해제는 차단을 유발하지 않는다 — {@code action} 이 그리는 축과 다른 축이다.
     *
     * @param academyId      대상 계정의 소속 학원. 메인 관리자 계정이면 {@code null}
     * @param actorAccountId 해제를 실행한 메인 관리자
     * @param actorLoginId   그 관리자의 로그인 아이디 스냅샷 — 계정이 나중에 사라져도 표시값이 남아야 한다
     * @param targetAccountId 차단이 풀린 계정
     * @param ip 해제를 요청한 관리자의 발신 IP(Ruling 595) — 요청 밖에서 불렀거나 IP 표기가 아니면 {@code null}
     */
    static AuditLog forAccountUnblock(Long academyId, Long actorAccountId, String actorLoginId,
            Long targetAccountId, String ip, OffsetDateTime occurredAt) {
        AuditLog log = new AuditLog(AuditCategory.LOGIN, AuditAction.UNBLOCK, occurredAt);
        log.academyId = academyId;
        log.actorAccountId = actorAccountId;
        log.actorLoginId = actorLoginId;
        log.targetType = TARGET_TYPE_ACCOUNT;
        log.targetId = targetAccountId;
        log.ip = ip;
        return log;
    }

    /**
     * 관계자가 학원 사용자의 비밀번호를 초기화한 사실(API_SPEC §5.22 · Ruling 329) — {@code action=update}.
     * 강제 확정({@link AuditLog#forRunForceConfirm})처럼 구별 문자열은 {@code detail.action} 에 둔다(Ruling 260).
     *
     * @param ip 요청 발신 IP(Ruling 580) — 요청 밖에서 불렀거나 IP 표기가 아니면 {@code null}
     */
    static AuditLog forAccountPasswordReset(Long academyId, Long actorAccountId, String actorLoginId,
            Long targetAccountId, String ip, OffsetDateTime occurredAt) {
        AuditLog log = new AuditLog(AuditCategory.DATA_ACCESS, AuditAction.UPDATE, occurredAt);
        log.academyId = academyId;
        log.actorAccountId = actorAccountId;
        log.actorLoginId = actorLoginId;
        log.targetType = TARGET_TYPE_ACCOUNT;
        log.targetId = targetAccountId;
        log.detail = Map.of("action", "account.password_reset");
        log.ip = ip;
        return log;
    }

    /**
     * 로그인 성공(SYS-02 · API_SPEC §2.5).
     *
     * @param academyId 로그인한 계정의 소속 학원. 메인 관리자 계정이면 {@code null}
     * @param ip        요청 발신 IP — {@code category=login} 에서만 채운다(ERD §3.4)
     */
    static AuditLog forLoginSuccess(Long academyId, Long actorAccountId, String actorLoginId, String ip,
            OffsetDateTime occurredAt) {
        AuditLog log = new AuditLog(AuditCategory.LOGIN, AuditAction.LOGIN_SUCCESS, occurredAt);
        log.academyId = academyId;
        log.actorAccountId = actorAccountId;
        log.actorLoginId = actorLoginId;
        log.targetType = TARGET_TYPE_ACCOUNT;
        log.targetId = actorAccountId;
        log.ip = ip;
        return log;
    }

    /**
     * 로그인 실패(SYS-02 · API_SPEC §2.5) — 비밀번호 불일치와 <b>존재하지 않는 로그인 아이디</b> 둘 다
     * 이 팩토리를 쓴다. ERD §3.4 이 {@code actor_account_id} 설명에 "로그인 실패는 계정 미확정
     * 가능성 존재" 라고 명시해, 계정을 특정하지 못한 실패도 기록 대상임을 전제한다 — 그 경우
     * {@code actorAccountId} 는 {@code null}, {@code targetId} 도 {@code null} 이고
     * {@code actorLoginId} 에는 시도된 문자열이 남는다(계정이 없어도 "누가 무엇으로
     * 시도했는지"는 감사 대상이다. 단 등록되지 않은 아이디({@code actorAccountId} 가 {@code null})는 가입 아이디 형식({@link LoginIdFormat})에 맞을 때만 남기고 그 밖은
     * {@code null} 로 바꾼다 — 아이디 칸에 잘못 넣은 비밀번호가 2년 보존되지 않게 한다, Ruling 937. 형식이 최대 50자라 {@code varchar(50)} 컬럼을 넘기지 않아
     * 저장이 실패해 로그인 응답이 {@code 500} 이 되는 일(BR-216)도 같은 규칙이 막는다).
     *
     * <p><b>차단된 계정의 대조 전 거부는 이 팩토리를 부르지 않는다</b>(Ruling 242 — 카운터도 안
     * 오르는 시도이므로 감사 이력에도 남기지 않는다. 심을 변형 4가 바로 이 경계를 시험한다).
     */
    static AuditLog forLoginFail(Long academyId, Long actorAccountId, String actorLoginId, String ip,
            OffsetDateTime occurredAt) {
        AuditLog log = new AuditLog(AuditCategory.LOGIN, AuditAction.LOGIN_FAIL, occurredAt);
        log.academyId = academyId;
        log.actorAccountId = actorAccountId;
        log.actorLoginId = actorAccountId == null && !LoginIdFormat.matches(actorLoginId) ? null : actorLoginId;
        log.targetType = TARGET_TYPE_ACCOUNT;
        log.targetId = actorAccountId;
        log.ip = ip;
        return log;
    }

    /**
     * 실패 누적이 상한(5회)에 닿아 계정이 {@code blocked} 로 전이된 바로 그 시도를 별도 행으로 남긴다.
     *
     * <p>{@link #forLoginFail} 과 <b>별행</b>인 이유 — 판정표 문면이 "5회 도달 →
     * {@code login_fail} + {@code block}(block_event=true) 행" 으로 둘을 나열하고, {@code action}
     * 값 도메인이 {@code login_fail}·{@code block} 을 별개 원소로 이미 갖고 있어(ERD §3.4) 한 행에
     * 두 action 을 동시에 담을 수 없다. {@code blockEvent=true} 는 이 행에만 서고
     * {@link #forLoginFail}·{@link #forLoginSuccess} 는 항상 false 다 — "이 시도가 차단을
     * 유발했는지" 축을 {@code action=block} 하나로 좁혀 둔 것이다.
     */
    static AuditLog forLoginBlock(Long academyId, Long actorAccountId, String actorLoginId, String ip,
            OffsetDateTime occurredAt) {
        AuditLog log = new AuditLog(AuditCategory.LOGIN, AuditAction.BLOCK, occurredAt);
        log.academyId = academyId;
        log.actorAccountId = actorAccountId;
        log.actorLoginId = actorLoginId;
        log.targetType = TARGET_TYPE_ACCOUNT;
        log.targetId = actorAccountId;
        log.ip = ip;
        log.blockEvent = true;
        return log;
    }
}
