package src.backend.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import src.backend.user.entity.Role;

/**
 * 회원가입 요청.
 * 가입 가능한 역할은 STUDENT·PARENT·DRIVER 뿐이며 tenantId 는 필수다.
 * ATTENDANT·ACADEMY_ADMIN·PLATFORM_ADMIN 은 관리자가 부여하는 역할이라 403 으로 거부된다.
 */
public record SignupRequest(
        @NotBlank @Email @Schema(example = "new.parent@school.com") String email,
        @NotBlank @Schema(example = "password") String password,
        @NotBlank @Schema(example = "김하늘") String name,
        @Schema(example = "010-1234-5678") String phone,
        @NotNull @Schema(example = "1", description = "소속 학원 id(한빛학원). 필수") Long tenantId,
        @NotNull @Schema(example = "PARENT") Role role) {
}
