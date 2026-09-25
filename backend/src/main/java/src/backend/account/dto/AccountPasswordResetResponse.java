package src.backend.account.dto;

/**
 * 관리자 경유 비밀번호 초기화 응답(API_SPEC §5.22) — {@code temporaryPassword} 는 1회 반환이라 재조회로는
 * 다시 못 본다(§6.7 과 같은 형태). {@code loginId} 는 아이디 분실 안내용이다.
 *
 * <p>{@code accountId} 는 문자열이다(API_SPEC §1.1 · Ruling 332).
 */
public record AccountPasswordResetResponse(String accountId, String loginId, String temporaryPassword) {
}
