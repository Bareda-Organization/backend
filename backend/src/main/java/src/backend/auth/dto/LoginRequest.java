package src.backend.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * 로그인 요청.
 */
public record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password) {
}
