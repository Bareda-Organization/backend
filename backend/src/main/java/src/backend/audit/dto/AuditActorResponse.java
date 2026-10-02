package src.backend.audit.dto;

/**
 * 감사 화면이 행위자를 고르는 목록의 항목 1개(API_SPEC §6.13 {@code GET /admin/audit-actors}).
 *
 * @param accountId   {@code account_id} 필터에 그대로 넘기는 값
 * @param role        {@code Role} 값을 소문자로 — {@code parent}·{@code student}·{@code driver}·{@code escort}·{@code staff}·
 *                    {@code system_admin}. 같은 이름을 구별하는 단서
 * @param academyName 소속 학원. 메인 관리자처럼 소속이 없으면 {@code null}
 */
public record AuditActorResponse(String accountId, String name, String loginId, String role, String academyName) {
}
