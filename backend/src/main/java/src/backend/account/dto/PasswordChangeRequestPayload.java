package src.backend.account.dto;

import jakarta.validation.constraints.NotBlank;

/** 비밀번호 변경 요청(API_SPEC §2.8). */
public record PasswordChangeRequestPayload(@NotBlank String currentPassword, @NotBlank String newPassword) {
}
