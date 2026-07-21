package src.backend.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import src.backend.user.entity.Role;

/**
 * 회원가입 요청.
 * PLATFORM_ADMIN 은 tenantId 없이 가입(전역 역할), 나머지 역할은 tenantId 필수.
 */
public record SignupRequest(
        @NotBlank @Email @Schema(example = "new.parent@school.com") String email,
        @NotBlank @Schema(example = "password") String password,
        @NotBlank @Schema(example = "김하늘") String name,
        @Schema(example = "010-1234-5678") String phone,
        @Schema(example = "1", description = "소속 학원 id(한빛학원). PLATFORM_ADMIN 가입 시 생략") Long tenantId,
        @NotNull @Schema(example = "PARENT") Role role) {
}
