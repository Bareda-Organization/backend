package src.backend.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 아이디·비밀번호 복구 요청(API_SPEC §2.9) — {@code verificationCode} 는 선택이며, 없으면 코드
 * 발송 요청이다. SMS 연동 전에는 형식 검증(422)만 거친 뒤 {@code 503} 으로 거절된다(Ruling 329).
 */
public record RecoverRequestPayload(
        @NotNull @Pattern(regexp = "login_id|password") String type,
        @NotBlank String phone,
        String verificationCode) {
}
