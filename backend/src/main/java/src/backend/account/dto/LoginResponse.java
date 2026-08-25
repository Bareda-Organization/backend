package src.backend.account.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 로그인 응답(API_SPEC §2.5) — {@code refresh_token} 은 {@code X-Client-Type: app} 일 때만 싣는다.
 * {@code web} 이면 이 필드가 {@code null} 이 아니라 JSON 자체에서 빠져야 하므로(§1.2.1) 클래스
 * 레벨에 {@link JsonInclude}({@code NON_NULL})를 둔다 — 개별 {@code @JsonProperty} 는 쓰지 않는다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoginResponse(
        String accessToken,
        String refreshToken,
        String role,
        String status,
        String accountId,
        Academy academy) {

    /** {@code system_admin} 은 소속 학원이 없어 {@code null} 이다(API_SPEC §2.5). */
    public record Academy(String id, String name) {
    }
}
