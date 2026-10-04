package src.backend.audit.dto;

import java.time.OffsetDateTime;

/**
 * 개인정보 조회·수정 이력 목록의 항목 1개(SYS-01, API_SPEC §6.13 {@code GET /admin/audit-logs}).
 *
 * @param actor      {@code AuditLog.actorLoginId} 스냅샷 — 계정이 나중에 사라져도 표시값이 남는다
 * @param actorName  행위자 계정의 <b>현재</b> 이름 — 계정을 못 찾으면 {@code null}(Ruling 809)
 * @param action     {@code read}·{@code update}·{@code delete} 중 하나(소문자, §6.13)
 * @param detailAction 감사 행 {@code detail.action} 원문 — 강제 확정 {@code run.force_confirm}·강제 종료 {@code run.force_finish} 등.
 *                   구별 문자열이 없는 행은 {@code null}(Ruling 809)
 * @param targetType {@code student}·{@code run_roster} 등 감사 대상 종류
 * @param targetId   대상 식별자 — 단일 학생 조회면 학생 id, 명단 조회면 회차(run) id
 * @param academyName 대상 감사 행의 소속 학원. 메인 관리자 자신의 동작이면 {@code null}
 * @param ip         기록한 접속 IP 그대로 — 남기지 않은 행은 {@code null}(Ruling 809)
 */
public record AuditLogItemResponse(String actor, String actorName, String action, String detailAction,
        String targetType, Long targetId, String academyName, String ip, OffsetDateTime occurredAt) {
}
