package src.backend.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * access 토큰 재발급 요청.
 */
public record RefreshRequest(
        @NotBlank @Schema(example = "(로그인 응답의 refreshToken 값을 그대로 사용)") String refreshToken) {
}
