package src.backend.audit.entity;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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
 * 감사·접속 이력 — 개인정보 조회·수정 이력과 로그인·차단 이력을 {@code category} 로 한 테이블에 담는다
 * (ERD §3.4 · FEATURE_SPEC §6).
 *
 * <p>{@code academy_id}·{@code actor_account_id} 는 논리적 부모이나 DB FK 가 미설정이다(ERD §4.2 —
 * 감사 대상이 삭제돼도 기록은 남아야 하므로 원본 삭제에 연동되면 감사 목적이 소멸한다). {@code action} 은
 * ERD 의 CHECK 값을 그대로 저장하며, "결과 성공/실패" · "차단 이벤트 여부" 로의 투영은 이 엔티티가 하지
 * 않는다(조율자 Ruling 13) — 조회 시점에 다른 Phase 가 계산한다.
 */
@Entity
@Table(name = "audit_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLog {

    /** {@code target_type} 이 계정을 가리킬 때의 값 — 조회 Phase 가 이 문자열로 대상 종류를 가른다. */
    private static final String TARGET_TYPE_ACCOUNT = "account";

    /** {@code target_type} 이 회차를 가리킬 때의 값({@link #forRunForceConfirm}). */
    private static final String TARGET_TYPE_RUN = "run";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "academy_id")
    private Long academyId;

    @Column(name = "actor_account_id")
    private Long actorAccountId;

    @Column(name = "actor_login_id", length = 50)
    private String actorLoginId;

    @Convert(converter = AuditCategory.Db.class)
    @Column(name = "category", length = 20, nullable = false)
    private AuditCategory category;

    @Convert(converter = AuditAction.Db.class)
    @Column(name = "action", length = 20, nullable = false)
    private AuditAction action;

    @Column(name = "target_type", length = 50)
    private String targetType;

    @Column(name = "target_id")
    private Long targetId;

    /** PostgreSQL {@code inet} 타입 — 기본 매핑(VARCHAR)으로 두면 {@code ddl-auto: validate} 가
     * "wrong column type" 으로 실패해 {@code SqlTypes.INET} 을 명시한다(엔티티 작성 규약 §4의
     * "inet→String" 을 실측으로 보완, 이 태스크 판단). {@code SqlTypes.OTHER} 는 검증은 통과하지만
     * 저장 시 pgjdbc 가 파라미터를 {@code bytea} 로 바인딩해 실패한다 — {@code SqlTypes.INET} 이
     * 검증·저장 둘 다 만족하는 값이다(Hibernate 7.4.1 기준 실측). */
    @JdbcTypeCode(SqlTypes.INET)
    @Column(name = "ip")
    private String ip;

    @Column(name = "block_event", nullable = false)
    private boolean blockEvent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail")
    private Map<String, Object> detail;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    private AuditLog(AuditCategory category, AuditAction action, OffsetDateTime occurredAt) {
        this.category = category;
        this.action = action;
        this.blockEvent = false;
        this.occurredAt = occurredAt;
    }

    /** 감사 대상 동작이 일어났을 때 최소 상태로 생성한다 — 행위자·대상 식별 필드는 도메인 Phase 가 채운다. */
    public static AuditLog forOccurrence(AuditCategory category, AuditAction action, OffsetDateTime occurredAt) {
        return new AuditLog(category, action, occurredAt);
    }

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
     */
    public static AuditLog forAccountUnblock(Long academyId, Long actorAccountId, String actorLoginId,
            Long targetAccountId, OffsetDateTime occurredAt) {
        AuditLog log = new AuditLog(AuditCategory.LOGIN, AuditAction.UNBLOCK, occurredAt);
        log.academyId = academyId;
        log.actorAccountId = actorAccountId;
        log.actorLoginId = actorLoginId;
        log.targetType = TARGET_TYPE_ACCOUNT;
        log.targetId = targetAccountId;
        return log;
    }

    /**
     * L3 필드가 실린 응답을 실제로 읽었을 때의 기록(SYS-01 · FEATURE_SPEC §6.3 · Phase 14 T1 목표 1).
     *
     * <p>요청 1건당 1행이다(Ruling 242 잠정) — 응답에 학생이 여러 명 실려도 행을 늘리지 않는다.
     * {@code detail} 에는 {@code student_ids}(응답에 실제로 실린 학생 식별자 전부)와
     * {@code fields}(노출된 L3 필드명 — {@code photo_url}·{@code note}·{@code guardian_phone}·
     * {@code address} 등) 만 담는다. 값 자체를 담지 않는 이유는 TECH_DECISIONS §13.2 가 로그에
     * 개인정보 원문을 금지해서다 — 무엇을 봤는지는 남기되 무엇이었는지는 남기지 않는다.
     *
     * @param targetType {@code student}·{@code run_roster} 등 Ruling 242 가 고정한 값 도메인
     * @param targetId   단일 학생 조회면 그 학생 id, 회차 명단이면 그 회차(run) id
     */
    public static AuditLog forDataAccessRead(Long academyId, Long actorAccountId, String actorLoginId,
            String targetType, Long targetId, Map<String, Object> detail, OffsetDateTime occurredAt) {
        AuditLog log = new AuditLog(AuditCategory.DATA_ACCESS, AuditAction.READ, occurredAt);
        log.academyId = academyId;
        log.actorAccountId = actorAccountId;
        log.actorLoginId = actorLoginId;
        log.targetType = targetType;
        log.targetId = targetId;
        log.detail = detail;
        return log;
    }

    /**
     * 로그인 성공(SYS-02 · API_SPEC §2.5).
     *
     * @param academyId 로그인한 계정의 소속 학원. 메인 관리자 계정이면 {@code null}
     * @param ip        요청 발신 IP — {@code category=login} 에서만 채운다(ERD §3.4)
     */
    public static AuditLog forLoginSuccess(Long academyId, Long actorAccountId, String actorLoginId, String ip,
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
     * {@code actorLoginId} 에는 시도된 문자열이 그대로 남는다(계정이 없어도 "누가 무엇으로
     * 시도했는지"는 감사 대상이다).
     *
     * <p><b>차단된 계정의 대조 전 거부는 이 팩토리를 부르지 않는다</b>(Ruling 242 — 카운터도 안
     * 오르는 시도이므로 감사 이력에도 남기지 않는다. 심을 변형 4가 바로 이 경계를 시험한다).
     */
    public static AuditLog forLoginFail(Long academyId, Long actorAccountId, String actorLoginId, String ip,
            OffsetDateTime occurredAt) {
        AuditLog log = new AuditLog(AuditCategory.LOGIN, AuditAction.LOGIN_FAIL, occurredAt);
        log.academyId = academyId;
        log.actorAccountId = actorAccountId;
        log.actorLoginId = actorLoginId;
        log.targetType = TARGET_TYPE_ACCOUNT;
        log.targetId = actorAccountId;
        log.ip = ip;
        return log;
    }

    /**
     * 실패 누적이 상한(5회)에 닿아 계정이 {@code blocked} 로 전이된 바로 그 시도를 별도 행으로 남긴다.
     *
     * <p>{@link #forLoginFail} 과 <b>별행</b>인 이유 — 목표 2 판정표 문면이 "5회 도달 →
     * {@code login_fail} + {@code block}(block_event=true) 행" 으로 둘을 나열하고, {@code action}
     * 값 도메인이 {@code login_fail}·{@code block} 을 별개 원소로 이미 갖고 있어(ERD §3.4) 한 행에
     * 두 action 을 동시에 담을 수 없다. {@code blockEvent=true} 는 이 행에만 서고
     * {@link #forLoginFail}·{@link #forLoginSuccess} 는 항상 false 다 — "이 시도가 차단을
     * 유발했는지" 축을 {@code action=block} 하나로 좁혀 둔 것이 이 태스크의 판단이다.
     */
    public static AuditLog forLoginBlock(Long academyId, Long actorAccountId, String actorLoginId, String ip,
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

    /**
     * 강제 확정 콘솔 개입(API_SPEC §6.14, F3 S2 목표 11)을 남긴다.
     *
     * <p><b>{@code category}·{@code action} 이 스펙 문면과 다르다.</b> §6.14 는 문면상 action 을
     * {@code run.force_confirm} 으로 적지만, {@code ck_audit_log_action} CHECK 제약(ERD §3.4)의 값
     * 도메인은 {@link AuditAction} 7종으로 닫혀 있어 그 문자열을 열 자체에는 담을 수 없다 — team-lead
     * 판정 대기 중 자체 판단으로 마감한 지점이다(SendMessage 395e631c-00fd-4bb6-8efb-c5dd951f048a,
     * 무응답). {@code category=DATA_ACCESS}·{@code action=UPDATE} 로 매핑한 이유는 이 개입이 로그인·
     * 차단 계열이 아니라 회차·경로 데이터를 실제로 바꾸는 쓰기이기 때문이다(다른 5개 값은 로그인 계열
     * 이거나 순수 조회라 더 멀다). 스펙이 요구하는 정확한 동작 이름은 {@code detail.action} 에
     * {@code "run.force_confirm"} 문자열 그대로 싣고, {@code reason}·{@code fallback_used} 도 같은
     * JSON 에 담아 열 자체가 좁아도 조회 시 원문을 잃지 않게 한다.
     *
     * @param actorAccountId 강제 확정을 실행한 메인 관리자
     * @param actorLoginId   그 관리자의 로그인 아이디 스냅샷
     * @param runId          강제 확정된 회차 — {@code target_id}
     * @param reason         강제 확정 사유(§6.14 요청 필드)
     * @param fallbackUsed   실제로 저장된 {@code route_version.fallback_used} 값
     */
    public static AuditLog forRunForceConfirm(Long actorAccountId, String actorLoginId, Long runId, String reason,
            boolean fallbackUsed, OffsetDateTime occurredAt) {
        AuditLog log = new AuditLog(AuditCategory.DATA_ACCESS, AuditAction.UPDATE, occurredAt);
        log.actorAccountId = actorAccountId;
        log.actorLoginId = actorLoginId;
        log.targetType = TARGET_TYPE_RUN;
        log.targetId = runId;
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("action", "run.force_confirm");
        detail.put("reason", reason);
        detail.put("fallback_used", fallbackUsed);
        log.detail = detail;
        return log;
    }
}
