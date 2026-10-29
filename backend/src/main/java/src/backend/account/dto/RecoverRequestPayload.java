package src.backend.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import src.backend.account.entity.PhoneNumber;

/**
 * 아이디·비밀번호 복구 요청(API_SPEC §2.9) — {@code verificationCode} 는 선택이며, 없으면 코드
 * 발송 요청이다. SMS 연동 전에는 형식 검증(422)만 거친 뒤 {@code 503} 으로 거절된다(Ruling 329).
 *
 * <p>{@code phone} 은 가입과 같은 연락처 규칙이다({@link PhoneNumber}) — 하이픈 있는 표기와 없는 표기를 모두 받고, 서비스가 표준형으로
 * 바꿔 코드 행을 쌓고 계정을 찾는다.
 */
public record RecoverRequestPayload(
        @NotNull @Pattern(regexp = "login_id|password") String type,
        @NotBlank @Pattern(regexp = PhoneNumber.PATTERN) String phone,
        String verificationCode) {
}
