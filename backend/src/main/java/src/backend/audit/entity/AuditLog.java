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
}
