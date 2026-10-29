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
 * 않는다(Ruling 13) — 조회 시점에 조회 쪽 로직이 계산한다.
 */
@Entity
@Table(name = "audit_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    Long id;

    @Column(name = "academy_id")
    Long academyId;

    @Column(name = "actor_account_id")
    Long actorAccountId;

    @Column(name = "actor_login_id", length = 50)
    String actorLoginId;

    @Convert(converter = AuditCategory.Db.class)
    @Column(name = "category", length = 20, nullable = false)
    AuditCategory category;

    @Convert(converter = AuditAction.Db.class)
    @Column(name = "action", length = 20, nullable = false)
    AuditAction action;

    @Column(name = "target_type", length = 50)
    String targetType;

    @Column(name = "target_id")
    Long targetId;

    /** PostgreSQL {@code inet} 타입 — 기본 매핑(VARCHAR)으로 두면 {@code ddl-auto: validate} 가
     * "wrong column type" 으로 실패해 {@code SqlTypes.INET} 을 명시한다(엔티티 작성 규약 §4의
     * "inet→String" 을 실측으로 보완).{@code SqlTypes.OTHER} 는 검증은 통과하지만
     * 저장 시 pgjdbc 가 파라미터를 {@code bytea} 로 바인딩해 실패한다 — {@code SqlTypes.INET} 이
     * 검증·저장 둘 다 만족하는 값이다(Hibernate 7.4.1 기준 실측). */
    @JdbcTypeCode(SqlTypes.INET)
    @Column(name = "ip")
    String ip;

    @Column(name = "block_event", nullable = false)
    boolean blockEvent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail")
    Map<String, Object> detail;

    @Column(name = "occurred_at", nullable = false)
    OffsetDateTime occurredAt;

    AuditLog(AuditCategory category, AuditAction action, OffsetDateTime occurredAt) {
        this.category = category;
        this.action = action;
        this.blockEvent = false;
        this.occurredAt = occurredAt;
    }

    /** 메인 관리자가 로그인 차단을 해제한 사실을 남긴다(AUTH-06 · API_SPEC §6.12) — 상세 설명은 {@link AuditLoginRows#forAccountUnblock} 참고. */
    public static AuditLog forAccountUnblock(Long academyId, Long actorAccountId, String actorLoginId,
            Long targetAccountId, String ip, OffsetDateTime occurredAt) {
        return AuditLoginRows.forAccountUnblock(academyId, actorAccountId, actorLoginId, targetAccountId, ip, occurredAt);
    }

    /** 관계자가 학원 사용자의 비밀번호를 초기화한 사실(API_SPEC §5.22 · Ruling 329) — 상세 설명은 {@link AuditLoginRows#forAccountPasswordReset} 참고. */
    public static AuditLog forAccountPasswordReset(Long academyId, Long actorAccountId, String actorLoginId,
            Long targetAccountId, String ip, OffsetDateTime occurredAt) {
        return AuditLoginRows.forAccountPasswordReset(academyId, actorAccountId, actorLoginId, targetAccountId, ip, occurredAt);
    }

    /** L3 필드가 실린 응답을 실제로 읽었을 때의 기록(SYS-01 · FEATURE_SPEC §6.3) — 상세 설명은 {@link AuditDataRows#forDataAccessRead} 참고. */
    public static AuditLog forDataAccessRead(Long academyId, Long actorAccountId, String actorLoginId, String targetType,
            Long targetId, Map<String, Object> detail, String ip, OffsetDateTime occurredAt) {
        return AuditDataRows.forDataAccessRead(academyId, actorAccountId, actorLoginId, targetType, targetId, detail, ip, occurredAt);
    }

    /** 로그인 성공(SYS-02 · API_SPEC §2.5) — 상세 설명은 {@link AuditLoginRows#forLoginSuccess} 참고. */
    public static AuditLog forLoginSuccess(Long academyId, Long actorAccountId,
            String actorLoginId, String ip, OffsetDateTime occurredAt) {
        return AuditLoginRows.forLoginSuccess(academyId, actorAccountId, actorLoginId, ip, occurredAt);
    }

    /** 로그인 실패(SYS-02 · API_SPEC §2.5) — 비밀번호 불일치와 존재하지 않는 로그인 아이디 둘 다 쓴다 — 상세 설명은 {@link AuditLoginRows#forLoginFail} 참고. */
    public static AuditLog forLoginFail(Long academyId, Long actorAccountId,
            String actorLoginId, String ip, OffsetDateTime occurredAt) {
        return AuditLoginRows.forLoginFail(academyId, actorAccountId, actorLoginId, ip, occurredAt);
    }

    /** 실패 누적이 상한(5회)에 닿아 계정이 {@code blocked} 로 전이된 바로 그 시도를 별도 행으로 남긴다 — 상세 설명은 {@link AuditLoginRows#forLoginBlock} 참고. */
    public static AuditLog forLoginBlock(Long academyId, Long actorAccountId,
            String actorLoginId, String ip, OffsetDateTime occurredAt) {
        return AuditLoginRows.forLoginBlock(academyId, actorAccountId, actorLoginId, ip, occurredAt);
    }

    /** 강제 확정 콘솔 개입(API_SPEC §6.14)을 남긴다 — 상세 설명은 {@link AuditDataRows#forRunForceConfirm} 참고. */
    public static AuditLog forRunForceConfirm(Long academyId, Long actorAccountId, String actorLoginId, Long runId,
            String reason, boolean fallbackUsed, Long routeVersionId, String ip, OffsetDateTime occurredAt) {
        return AuditDataRows.forRunForceConfirm(academyId, actorAccountId, actorLoginId, runId, reason, fallbackUsed, routeVersionId, ip, occurredAt);
    }

    /** 운행일이 지난 이동 중 회차의 강제 종료(API_SPEC §6.17, Ruling 724) — 상세 설명은 {@link AuditDataRows#forRunForceFinish} 참고. */
    public static AuditLog forRunForceFinish(Long academyId, Long actorAccountId, String actorLoginId, Long runId,
            String reason, long boardedCount, String ip, OffsetDateTime occurredAt) {
        return AuditDataRows.forRunForceFinish(academyId, actorAccountId, actorLoginId, runId, reason, boardedCount, ip, occurredAt);
    }

    /** 관계자·메인 관리자가 L3 필드를 고치거나({@code update}) 학생을 퇴원시킨({@code delete}) 사실(Ruling 333 · SYS-01) — 상세 설명은 {@link AuditDataRows#forDataAccessChange} 참고. */
    public static AuditLog forDataAccessChange(AuditAction action, Long academyId, Long actorAccountId, String actorLoginId, String targetType,
            Long targetId, Map<String, Object> detail, String ip, OffsetDateTime occurredAt) {
        return AuditDataRows.forDataAccessChange(action, academyId, actorAccountId, actorLoginId, targetType, targetId, detail, ip, occurredAt);
    }
}
