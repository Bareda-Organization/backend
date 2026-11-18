package src.backend.account.dto;

import java.nio.charset.StandardCharsets;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import src.backend.account.entity.LoginIdFormat;
import src.backend.account.entity.PasswordRule;
import src.backend.account.entity.PhoneNumber;

/**
 * 회원가입 요청(API_SPEC §2.2). {@code role} 은 문자열로 받는다 — 메인 관리자(system_admin)를
 * 여기서 신청할 수 없다는 사양 제약을 {@link Pattern} 이 값 자체에서 차단한다(허용 5종만 나열).
 * JSON 필드명은 전역 {@code spring.jackson.property-naming-strategy: SNAKE_CASE}(Ruling 104)가 변환한다.
 */
public record SignupRequestPayload(
        @NotBlank @Pattern(regexp = "parent|student|driver|escort|staff") String role,
        @NotBlank @Pattern(regexp = LoginIdFormat.PATTERN) String loginId,
        @NotBlank @Size(min = PasswordRule.MIN_LENGTH, message = PasswordRule.MIN_LENGTH_MESSAGE) String password,
        @NotBlank @Size(max = 50) String name,
        @NotBlank @Pattern(regexp = PhoneNumber.PATTERN) String phone,
        @NotBlank String academyId) {

    /**
     * BCrypt 는 72바이트까지만 받는다 — 넘기면 저장 시 {@code IllegalArgumentException} 으로 500 이 된다(BR-092).
     * 글자 수가 아니라 UTF-8 바이트라 한글은 24자가 상한이다.
     */
    @AssertTrue
    public boolean isPasswordWithinBcryptLimit() {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}
