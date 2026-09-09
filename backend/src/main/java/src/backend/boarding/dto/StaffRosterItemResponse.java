package src.backend.boarding.dto;

/**
 * 관계자 웹의 호차별 일일 명단 1행(API_SPEC §5.4 {@code GET /staff/runs/{runId}/roster}, RST-03·A-04,
 * Phase 9 목표 6).
 *
 * <p>{@code guardianPhone} 이 원문이다 — 관계자 웹은 마스킹 대상 밖(§5.4·§1.12 L2)이라
 * {@code ManagerRosterResponse} 와 갈리는 지점이 이 필드 하나다. {@code absent} 학생도 행으로
 * 남는다({@code status=absent}) — 매니저 앱(행 제외)과 반대다.
 */
public record StaffRosterItemResponse(Long studentId, String name, String className, String stopName,
        String guardianPhone, String change, String status, String note) {
}
