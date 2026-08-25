package src.backend.account.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 회원가입 요청(API_SPEC §2.2). {@code role} 은 문자열로 받는다 — 메인 관리자(system_admin)를
 * 여기서 신청할 수 없다는 사양 제약을 {@link Pattern} 이 값 자체에서 차단한다(허용 5종만 나열).
 */
public record SignupRequestPayload(
        @NotBlank @Pattern(regexp = "parent|student|driver|escort|staff") String role,
        @JsonProperty("login_id") @NotBlank String loginId,
        @NotBlank String password,
        @NotBlank String name,
        @NotBlank String phone,
        @JsonProperty("academy_id") @NotBlank String academyId) {
}
