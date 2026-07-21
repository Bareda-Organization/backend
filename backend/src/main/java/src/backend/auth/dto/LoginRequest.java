package src.backend.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * 로그인 요청.
 */
public record LoginRequest(
        @NotBlank @Email @Schema(example = "admin@school.com") String email,
        @NotBlank @Schema(example = "password") String password) {
}
