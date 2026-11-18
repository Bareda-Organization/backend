package src.backend.account.dto;

import java.nio.charset.StandardCharsets;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import src.backend.account.entity.PasswordRule;

/** 비밀번호 변경 요청(API_SPEC §2.8). */
public record PasswordChangeRequestPayload(@NotBlank String currentPassword,
        @NotBlank @Size(min = PasswordRule.MIN_LENGTH, message = PasswordRule.MIN_LENGTH_MESSAGE) String newPassword) {

    /**
     * BCrypt 는 72바이트까지만 받는다 — 넘기면 저장 시 {@code IllegalArgumentException} 으로 500 이 된다(BR-092).
     * 글자 수가 아니라 UTF-8 바이트라 한글은 24자가 상한이다.
     */
    @AssertTrue
    public boolean isPasswordWithinBcryptLimit() {
        return newPassword == null || newPassword.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}
