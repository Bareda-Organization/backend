package src.backend.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 관리자 강제 비밀번호 재설정 요청.
 *
 * <p>관리자가 대신 초기화하는 경로라 현재 비밀번호를 묻지 않는다.
 * 응답에는 이 값을 되돌려주지 않는다 — 로그·프록시 캐시에 평문이 남는다.
 */
public record ResetPasswordRequest(
        @NotBlank @Size(min = 8) @Schema(example = "newpassword") String newPassword) {
}
