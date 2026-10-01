package src.backend.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 아이디·비밀번호 복구 요청(API_SPEC §2.9) — {@code verificationCode} 는 선택이며, 없으면 코드
 * 발송 요청이다. SMS 연동 전에는 형식 검증(422)만 거친 뒤 {@code 503} 으로 거절된다(Ruling 329).
 *
 * <p>{@code phone} 상한 20자는 {@code verification_code.phone} 컬럼 길이다 — 계정 연락처(30자)가 더 길어도 문자로
 * 복구할 수 없고(422) 관리자 경유(§5.22)로 풀어야 한다. 넘기면 코드 행 저장에서 500 이 난다.
 */
public record RecoverRequestPayload(
        @NotNull @Pattern(regexp = "login_id|password") String type,
        @NotBlank @Size(max = 20) String phone,
        String verificationCode) {
}
