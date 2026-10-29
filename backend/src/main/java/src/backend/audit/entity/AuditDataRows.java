package src.backend.audit.entity;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 개인정보(L3) 조회 · 변경과 운영 개입 감사 행의 팩토리(SYS-01 · API_SPEC §6.14 · §6.17) — {@link AuditLog} 의 같은 이름 팩토리가 이 클래스로
 * 위임한다. 행은 행위자 스냅샷(학원 · 계정 · 로그인 아이디 · IP)과 시각을 값으로 받는 불변 기록이다.
 */
final class AuditDataRows {

    /** {@code target_type} 이 회차를 가리킬 때의 값({@link #forRunForceConfirm} · {@link #forRunForceFinish}). */
    private static final String TARGET_TYPE_RUN = "run";

    /**
     * L3 필드가 실린 응답을 실제로 읽었을 때의 기록(SYS-01 · FEATURE_SPEC §6.3).
     *
     * <p>요청 1건당 1행이다(Ruling 242 잠정) — 응답에 학생이 여러 명 실려도 행을 늘리지 않는다.
     * {@code detail} 에는 {@code student_ids}(응답에 실제로 실린 학생 식별자 전부)와
     * {@code fields}(노출된 L3 필드명 — {@code photo_url}·{@code note}·{@code guardian_phone}·
     * {@code address} 등) 만 담는다. 값 자체를 담지 않는 이유는 TECH_DECISIONS §13.2 가 로그에
     * 개인정보 원문을 금지해서다 — 무엇을 봤는지는 남기되 무엇이었는지는 남기지 않는다.
     *
     * @param targetType {@code student}·{@code run_roster} 등 Ruling 242 가 고정한 값 도메인
     * @param targetId   단일 학생 조회면 그 학생 id, 회차 명단이면 그 회차(run) id
     * @param ip         요청 발신 IP — 요청 밖에서 불렀거나 IP 표기가 아니면 {@code null}
     */
    static AuditLog forDataAccessRead(Long academyId, Long actorAccountId, String actorLoginId,
            String targetType, Long targetId, Map<String, Object> detail, String ip, OffsetDateTime occurredAt) {
        AuditLog log = new AuditLog(AuditCategory.DATA_ACCESS, AuditAction.READ, occurredAt);
        log.academyId = academyId;
        log.actorAccountId = actorAccountId;
        log.actorLoginId = actorLoginId;
        log.targetType = targetType;
        log.targetId = targetId;
        log.detail = detail;
        log.ip = ip;
        return log;
    }

    /**
     * 강제 확정 콘솔 개입(API_SPEC §6.14)을 남긴다.
     *
     * <p><b>{@code category}·{@code action} 이 스펙 문면과 다르다 — Ruling 260 확정.</b> §6.14 는
     * 문면상 action 을 {@code run.force_confirm} 으로 적지만, {@code ck_audit_log_action} CHECK
     * 제약의 값 도메인은 {@link AuditAction} 7종으로 닫혀 있어 그 문자열을 열 자체에는 담을 수 없다.
     * Ruling 260 에서 {@code category=DATA_ACCESS}·{@code action=UPDATE}·
     * {@code target_type="run"} 재사용을 확정했다 — 도메인을 늘리면 V1 CHECK·ERD CHECK·
     * {@code GET /admin/audit-logs}(§6.12) 의 action 투영까지 번지고, {@code update}+{@code run} 이면
     * 같은 감사 조회 화면에 자연히 나타나기 때문이다. 스펙이 요구하는 정확한 동작 이름·부가 정보는
     * {@code detail} JSON 에 그대로 싣는다 — {@code action}(문자열 {@code "run.force_confirm"}),
     * {@code reason}, {@code fallback_used}, {@code route_version_id}.
     *
     * @param academyId      회차의 학원 — 없으면 §6.13 {@code academy_id} 필터 조회에서 빠진다(BR-129)
     * @param actorAccountId 강제 확정을 실행한 메인 관리자
     * @param actorLoginId   그 관리자의 로그인 아이디 스냅샷
     * @param runId          강제 확정된 회차 — {@code target_id}
     * @param reason         강제 확정 사유(§6.14 요청 필드)
     * @param fallbackUsed   실제로 저장된 {@code route_version.fallback_used} 값
     * @param routeVersionId 신규 생성된 {@code route_version} 의 id
     * @param ip             요청 발신 IP — 요청 밖에서 불렀거나 IP 표기가 아니면 {@code null}
     */
    static AuditLog forRunForceConfirm(Long academyId, Long actorAccountId, String actorLoginId, Long runId,
            String reason, boolean fallbackUsed, Long routeVersionId, String ip, OffsetDateTime occurredAt) {
        AuditLog log = new AuditLog(AuditCategory.DATA_ACCESS, AuditAction.UPDATE, occurredAt);
        log.academyId = academyId;
        log.actorAccountId = actorAccountId;
        log.actorLoginId = actorLoginId;
        log.targetType = TARGET_TYPE_RUN;
        log.targetId = runId;
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("action", "run.force_confirm");
        detail.put("reason", reason);
        detail.put("fallback_used", fallbackUsed);
        detail.put("route_version_id", routeVersionId);
        log.detail = detail;
        log.ip = ip;
        return log;
    }

    /**
     * 운행일이 지난 이동 중 회차의 강제 종료(API_SPEC §6.17, Ruling 724) — {@link #forRunForceConfirm} 과 같은 형태로
     * {@code category=data_access}·{@code action=update}·{@code target_type=run} 을 재사용하고, 구별 문자열
     * {@code run.force_finish} · {@code reason} · 종료 시점에 아직 {@code boarded} 였던 탑승자 수를 {@code detail} 에 싣는다.
     *
     * @param academyId     회차의 학원 — 없으면 §6.13 {@code academy_id} 필터 조회에서 빠진다(BR-129)
     * @param boardedCount  강제 종료로 하차 처리 없이 남겨진 탑승자 수 — 나중에 "누구를 두고 끝냈나" 를 되짚는 재료
     * @param ip            요청 발신 IP — 요청 밖에서 불렀거나 IP 표기가 아니면 {@code null}
     */
    static AuditLog forRunForceFinish(Long academyId, Long actorAccountId, String actorLoginId, Long runId,
            String reason, long boardedCount, String ip, OffsetDateTime occurredAt) {
        AuditLog log = new AuditLog(AuditCategory.DATA_ACCESS, AuditAction.UPDATE, occurredAt);
        log.academyId = academyId;
        log.actorAccountId = actorAccountId;
        log.actorLoginId = actorLoginId;
        log.targetType = TARGET_TYPE_RUN;
        log.targetId = runId;
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("action", "run.force_finish");
        detail.put("reason", reason);
        detail.put("boarded_count", boardedCount);
        log.detail = detail;
        log.ip = ip;
        return log;
    }

    /**
     * 관계자·메인 관리자가 L3 필드를 고치거나({@code update}) 학생을 퇴원시킨({@code delete}) 사실(Ruling 333 ·
     * SYS-01). {@code detail} 에는 바뀐 필드명만 담고 값은 담지 않는다 — {@link #forDataAccessRead} 와 같은 이유.
     *
     * @param ip 요청 발신 IP — 요청 밖에서 불렀거나 IP 표기가 아니면 {@code null}
     */
    static AuditLog forDataAccessChange(AuditAction action, Long academyId, Long actorAccountId,
            String actorLoginId, String targetType, Long targetId, Map<String, Object> detail, String ip,
            OffsetDateTime occurredAt) {
        AuditLog log = new AuditLog(AuditCategory.DATA_ACCESS, action, occurredAt);
        log.academyId = academyId;
        log.actorAccountId = actorAccountId;
        log.actorLoginId = actorLoginId;
        log.targetType = targetType;
        log.targetId = targetId;
        log.detail = detail;
        log.ip = ip;
        return log;
    }
}
