package src.backend.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import src.backend.user.entity.Role;

/**
 * 구성원(기사·학부모·학생·학원관리자) 등록 요청 — 관리자가 계정 + 학원 멤버십을 함께 만든다.
 *
 * <p>tenantId 생략 시 요청자(학원 관리자)의 소속 학원으로 만든다.
 * PLATFORM_ADMIN 역할은 이 경로로 만들 수 없다(전역 관리자는 signup/seed 전용).
 */
public record CreateMemberRequest(
        @NotBlank @Email @Schema(example = "new.driver@school.com") String email,
        @NotBlank @Schema(example = "password") String password,
        @NotBlank @Schema(example = "정기사") String name,
        @Schema(example = "010-2345-6789") String phone,
        @Schema(example = "1", description = "소속 학원 id(한빛학원)") Long tenantId,
        @NotNull @Schema(example = "DRIVER") Role role) {
}
