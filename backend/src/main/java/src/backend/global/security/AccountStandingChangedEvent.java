package src.backend.global.security;

/**
 * 계정의 권한 상태(차단 · 퇴사 · 삭제 여부)가 바뀌었다는 알림(R57 RT-1) — 커밋 직후 {@link AccountStanding} 의 그 계정 보관 항목을 비운다.
 * 퇴사 · 매니저 삭제는 각자 이미 있는 이벤트({@code StaffResignedEvent} · {@code ManagerDeletedEvent})를 같은 리스너가 받는다.
 */
public record AccountStandingChangedEvent(Long accountId) {
}
