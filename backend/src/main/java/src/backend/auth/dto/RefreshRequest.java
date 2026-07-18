package src.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * access 토큰 재발급 요청.
 */
public record RefreshRequest(
        @NotBlank String refreshToken) {
}
