package src.backend.account.dto;

/**
 * refresh 요청(API_SPEC §2.6) — 앱은 이 필드에 refresh 토큰을 담고, 웹은 쿠키로 보내 본문이 비어
 * 있다({@code refreshToken} 이 {@code null}). 서버는 쿠키를 먼저 보고 없으면 이 값을 읽는다
 * (판정은 {@code AuthController} 한 곳에만 둔다 — Ruling, 브리프 §3).
 */
public record RefreshRequestPayload(String refreshToken) {
}
