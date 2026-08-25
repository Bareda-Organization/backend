package src.backend.audit.entity;

import java.time.OffsetDateTime;
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
 * (ERD §3.6 · FEATURE_SPEC §6).
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
     * <p>{@code category} 가 {@link AuditCategory#LOGIN} 인 이유는 ERD §3.6 이 {@code block}·
     * {@code unblock} 을 로그인·차단 이력 쪽 값으로 정의하고, §6.13 의 접속 이력 조회가 이 두 값을
     * {@code block_event} 로 투영하기 때문이다 — {@code data_access} 로 넣으면 그 화면에서 사라진다.
     *
     * <p>{@code blockEvent} 는 false 로 둔다. 그 컬럼은 "이 <b>시도</b>가 차단을 유발했는지" 를 뜻하며
     * (ERD §3.6), 해제는 차단을 유발하지 않는다 — {@code action} 이 그리는 축과 다른 축이다.
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
}
