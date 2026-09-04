package src.backend.audit.dto;

import java.time.OffsetDateTime;

/**
 * 로그인·차단 이력 목록의 항목 1개(SYS-02, API_SPEC §6.13 {@code GET /admin/login-history}).
 *
 * <p>{@code result}·{@code blockEvent} 는 저장된 {@code AuditLog.action} 값에서 조회 시점에
 * 다시 계산한 값이다(ERD §3.6 — 엔티티 자신은 이 투영을 하지 않는다, 조율자 Ruling 13) —
 * {@code login_success}→{@code result=success}, {@code login_fail}→{@code result=fail},
 * {@code block}·{@code unblock}→{@code blockEvent=true}. 저장된 {@code block_event} 컬럼값을
 * 그대로 옮기지 않는다 — 그 컬럼은 "이 시도가 차단을 유발했는지"(로그인 실패·차단 행만 해당)를
 * 뜻해, 응답이 요구하는 "이 행이 차단 이벤트인가"(해제 포함)와 축이 다르다.
 *
 * @param accountId {@code AuditLog.actorAccountId} — 시도한 계정. 등록되지 않은 로그인 아이디로
 *                  실패한 행은 {@code null}
 * @param loginId   {@code AuditLog.actorLoginId} 스냅샷 — 계정이 없어도 시도된 문자열이 남는다
 * @param result    {@code success}·{@code fail} 중 하나. {@code block}·{@code unblock} 행은
 *                  결과 축이 아니라 {@code blockEvent} 축이라 {@code null}
 */
public record LoginHistoryItemResponse(Long accountId, String loginId, String result, String ip,
        OffsetDateTime occurredAt, boolean blockEvent) {
}
