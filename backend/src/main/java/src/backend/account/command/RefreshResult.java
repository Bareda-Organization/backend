package src.backend.account.command;

/**
 * refresh 성공 결과(내부 전달용) — 새 access 토큰과 회전된 refresh 원문만 담는다. 이 결과를
 * 본문에 실을지 쿠키로 내릴지는 컨트롤러가 판단한다(브리프 §3).
 */
public record RefreshResult(String accessToken, String refreshToken, long refreshTokenValiditySeconds) {
}
