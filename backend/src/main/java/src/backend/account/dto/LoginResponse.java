package src.backend.account.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 로그인 응답(API_SPEC §2.5) — {@code refresh_token} 은 {@code X-Client-Type: app} 일 때만 싣는다.
 * {@code web} 이면 이 필드가 {@code null} 이 아니라 JSON 자체에서 빠져야 한다(§1.2.1).
 *
 * <p>{@link JsonInclude}({@code NON_NULL})를 <b>클래스가 아니라 그 필드에만</b> 붙인다 — 클래스에
 * 붙이면 {@code academy} 까지 걸려 {@code system_admin} 응답에서 키가 통째로 사라지는데, §2.5 표는
 * {@code academy} 를 "{@code system_admin} 은 {@code null}" 로 규정해 <b>키는 있고 값이 null</b> 이어야
 * 한다(리뷰 라운드 1 m2). 키가 빠지면 클라이언트가 null 검사와 키 존재 검사 중 무엇을 쓸지 갈린다.
 */
public record LoginResponse(
        String accessToken,
        @JsonInclude(JsonInclude.Include.NON_NULL) String refreshToken,
        String role,
        String status,
        String accountId,
        Academy academy) {

    /** {@code system_admin} 은 소속 학원이 없어 {@code null} 이다(API_SPEC §2.5). */
    public record Academy(String id, String name) {
    }
}
