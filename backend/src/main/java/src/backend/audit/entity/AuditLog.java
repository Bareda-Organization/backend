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
 *
 * <p>팩토리의 공통 인자 — {@code academyId} 는 감사 대상 자원의 소속 학원(메인 관리자 · 시스템이 행위자면 {@code null}), {@code actorAccountId} 는 행위자 계정 id,
 * {@code actorLoginId} 는 행위자 로그인 아이디 스냅샷(계정이 사라져도 표시값이 남는다; 로그인 실패 행은 시도된 아이디이고 가입 아이디 형식이 아니면 팩토리가 {@code null} 로 비움),
 * {@code ip} 는 요청 발신 IP(요청 밖에서 불렀거나 IP 표기가 아니면 {@code null}), {@code occurredAt} 은 사건 시각이다. 각 팩토리 설명은 그 밖의 인자와 저장되는
 * {@code category} · {@code action} · {@code target_type} · {@code detail} 만 적는다.
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

    /**
     * 메인 관리자가 로그인 차단을 해제한 사실을 남긴다(AUTH-06 · API_SPEC §6.12).
     * <p>저장값: {@code category=login} · {@code action=unblock} · {@code target_type=account}.
     * @param targetAccountId 차단이 풀린 계정 id — {@code target_id} 에 저장
     */
    public static AuditLog forAccountUnblock(Long academyId, Long actorAccountId, String actorLoginId,
            Long targetAccountId, String ip, OffsetDateTime occurredAt) {
        return AuditLoginRows.forAccountUnblock(academyId, actorAccountId, actorLoginId, targetAccountId, ip, occurredAt);
    }

    /**
     * 관계자가 학원 사용자의 비밀번호를 초기화한 사실(API_SPEC §5.22 · Ruling 329).
     * <p>저장값: {@code category=data_access} · {@code action=update} · {@code target_type=account},
     * {@code detail} 은 {@code {"action": "account.password_reset"}}.
     * @param targetAccountId 비밀번호가 초기화된 계정 id — {@code target_id} 에 저장
     */
    public static AuditLog forAccountPasswordReset(Long academyId, Long actorAccountId, String actorLoginId,
            Long targetAccountId, String ip, OffsetDateTime occurredAt) {
        return AuditLoginRows.forAccountPasswordReset(academyId, actorAccountId, actorLoginId, targetAccountId, ip, occurredAt);
    }

    /**
     * L3 필드가 실린 응답을 실제로 읽었을 때의 기록(SYS-01 · FEATURE_SPEC §6.3). 요청 1건당 1행이다.
     * <p>저장값: {@code category=data_access} · {@code action=read}. {@code target_type}·{@code target_id}·{@code detail} 은
     * 인자 그대로 저장한다. {@code detail} 에는 학생 식별자 목록({@code student_ids})과 노출된 L3 필드명({@code fields})만 담고 값은 담지 않는다.
     * @param targetType     대상 종류 — {@code student}·{@code run_roster} 등
     * @param targetId       단일 학생 조회면 학생 id, 회차 명단이면 회차 id
     * @param detail         응답에 실린 학생 식별자 전부와 노출된 L3 필드명
     */
    public static AuditLog forDataAccessRead(Long academyId, Long actorAccountId, String actorLoginId, String targetType,
            Long targetId, Map<String, Object> detail, String ip, OffsetDateTime occurredAt) {
        return AuditDataRows.forDataAccessRead(academyId, actorAccountId, actorLoginId, targetType, targetId, detail, ip, occurredAt);
    }

    /**
     * 로그인 성공(SYS-02 · API_SPEC §2.5).
     * <p>저장값: {@code category=login} · {@code action=login_success} · {@code target_type=account}.
     * {@code target_id} 는 {@code actorAccountId} 와 같다.
     */
    public static AuditLog forLoginSuccess(Long academyId, Long actorAccountId,
            String actorLoginId, String ip, OffsetDateTime occurredAt) {
        return AuditLoginRows.forLoginSuccess(academyId, actorAccountId, actorLoginId, ip, occurredAt);
    }

    /**
     * 로그인 실패(SYS-02 · API_SPEC §2.5) — 비밀번호 불일치와 존재하지 않는 로그인 아이디 둘 다 쓴다.
     * <p>저장값: {@code category=login} · {@code action=login_fail} · {@code target_type=account}.
     * {@code target_id} 는 {@code actorAccountId} 와 같다.
     */
    public static AuditLog forLoginFail(Long academyId, Long actorAccountId,
            String actorLoginId, String ip, OffsetDateTime occurredAt) {
        return AuditLoginRows.forLoginFail(academyId, actorAccountId, actorLoginId, ip, occurredAt);
    }

    /**
     * 실패 누적이 상한(5회)에 닿아 계정이 {@code blocked} 로 전이된 바로 그 시도를 별도 행으로 남긴다.
     * <p>저장값: {@code category=login} · {@code action=block} · {@code target_type=account} · {@code block_event=true}.
     * {@code target_id} 는 {@code actorAccountId} 와 같다.
     */
    public static AuditLog forLoginBlock(Long academyId, Long actorAccountId,
            String actorLoginId, String ip, OffsetDateTime occurredAt) {
        return AuditLoginRows.forLoginBlock(academyId, actorAccountId, actorLoginId, ip, occurredAt);
    }

    /**
     * 강제 확정 콘솔 개입(API_SPEC §6.14)을 남긴다.
     * <p>저장값: {@code category=data_access} · {@code action=update} · {@code target_type=run} · {@code target_id=runId}.
     * {@code detail} 에는 {@code action=run.force_confirm}, {@code reason}, {@code fallback_used}, {@code route_version_id} 를 담는다.
     * @param runId          강제 확정된 회차 id
     * @param reason         강제 확정 사유 — {@code detail.reason}
     * @param fallbackUsed   저장된 {@code route_version.fallback_used} 값 — {@code detail.fallback_used}
     * @param routeVersionId 새로 만든 {@code route_version} 의 id — {@code detail.route_version_id}
     */
    public static AuditLog forRunForceConfirm(Long academyId, Long actorAccountId, String actorLoginId, Long runId,
            String reason, boolean fallbackUsed, Long routeVersionId, String ip, OffsetDateTime occurredAt) {
        return AuditDataRows.forRunForceConfirm(academyId, actorAccountId, actorLoginId, runId, reason, fallbackUsed, routeVersionId, ip, occurredAt);
    }

    /**
     * 운행일이 지난 이동 중 회차의 강제 종료(API_SPEC §6.17, Ruling 724).
     * <p>저장값: {@code category=data_access} · {@code action=update} · {@code target_type=run} · {@code target_id=runId}.
     * {@code detail} 에는 {@code action=run.force_finish}, {@code reason}, {@code boarded_count} 를 담는다.
     * @param runId          강제 종료된 회차 id
     * @param reason         강제 종료 사유 — {@code detail.reason}
     * @param boardedCount   종료 시점에 아직 {@code boarded} 였던 탑승자 수 — 하차 처리 없이 남겨진 인원, {@code detail.boarded_count}
     */
    public static AuditLog forRunForceFinish(Long academyId, Long actorAccountId, String actorLoginId, Long runId,
            String reason, long boardedCount, String ip, OffsetDateTime occurredAt) {
        return AuditDataRows.forRunForceFinish(academyId, actorAccountId, actorLoginId, runId, reason, boardedCount, ip, occurredAt);
    }

    /**
     * 관계자·메인 관리자가 L3 필드를 고치거나({@code update}) 학생을 퇴원시킨({@code delete}) 사실(Ruling 333 · SYS-01).
     * <p>저장값: {@code category=data_access} · {@code action} 은 인자 그대로({@code update} 또는 {@code delete}) ·
     * {@code target_type}·{@code target_id}·{@code detail} 도 인자 그대로 저장한다. {@code detail} 에는 바뀐 필드명만 담고 값은 담지 않는다.
     * @param action         {@code update} 또는 {@code delete}
     * @param targetType     대상 종류 문자열
     * @param targetId       대상 id
     * @param detail         바뀐 필드명 목록
     */
    public static AuditLog forDataAccessChange(AuditAction action, Long academyId, Long actorAccountId, String actorLoginId, String targetType,
            Long targetId, Map<String, Object> detail, String ip, OffsetDateTime occurredAt) {
        return AuditDataRows.forDataAccessChange(action, academyId, actorAccountId, actorLoginId, targetType, targetId, detail, ip, occurredAt);
    }
}
