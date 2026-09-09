package src.backend.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 아이디·비밀번호 복구 요청(API_SPEC §2.9) — {@code verificationCode} 는 선택이며, 없으면 코드
 * 발송 요청으로 처리한다.
 */
public record RecoverRequestPayload(
        @NotNull String type,
        @NotBlank String phone,
        String verificationCode) {
}
