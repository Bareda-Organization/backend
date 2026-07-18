package src.backend.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import src.backend.user.entity.Role;

/**
 * 회원가입 요청.
 * PLATFORM_ADMIN 은 tenantId 없이 가입(전역 역할), 나머지 역할은 tenantId 필수.
 */
public record SignupRequest(
        @NotBlank @Email String email,
        @NotBlank String password,
        @NotBlank String name,
        String phone,
        Long tenantId,
        @NotNull Role role) {
}
