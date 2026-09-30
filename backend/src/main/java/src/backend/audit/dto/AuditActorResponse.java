package src.backend.audit.dto;

/**
 * 감사 화면이 행위자를 고르는 목록의 항목 1개(API_SPEC §6.13 {@code GET /admin/audit-actors}).
 *
 * @param accountId   {@code account_id} 필터에 그대로 넘기는 값
 * @param role        {@code staff}·{@code manager}·{@code system_admin} 등 소문자 역할 — 같은 이름을 구별하는 단서
 * @param academyName 소속 학원. 메인 관리자처럼 소속이 없으면 {@code null}
 */
public record AuditActorResponse(String accountId, String name, String loginId, String role, String academyName) {
}
