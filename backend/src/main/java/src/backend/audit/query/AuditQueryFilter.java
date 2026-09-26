package src.backend.audit.query;

/**
 * 감사 로그·로그인 이력 조회가 공유하는 필터(§6.13) — {@link AuditLogQueryService#list}·
 * {@link LoginHistoryQueryService#list} 가 같은 6개 파라미터를 각자 받던 것을 하나로 묶었다(BR-101,
 * {@code CODE_CONVENTIONS §20.2} 파라미터 4개 초과 → record).
 */
public record AuditQueryFilter(Long academyId, Long accountId, String from, String to, Integer page, Integer size) {
}
