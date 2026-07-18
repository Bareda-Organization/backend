package src.backend.auth.dto;

/**
 * 로그인·재발급 응답. access 는 API 호출용(짧게), refresh 는 재발급용(길게).
 */
public record TokenResponse(
        String accessToken,
        String refreshToken) {
}
