package src.backend.account.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * refresh 응답(API_SPEC §2.6) — 회전된 refresh 토큰은 앱 호출일 때만 본문에 싣는다. 웹 호출이면
 * {@code null} 이 아니라 필드 자체가 빠져야 해서 {@link LoginResponse} 와 같은 이유로
 * {@code NON_NULL} 을 클래스에 둔다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RefreshResponse(String accessToken, String refreshToken) {
}
