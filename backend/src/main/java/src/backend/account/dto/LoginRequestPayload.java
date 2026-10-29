package src.backend.account.dto;

import java.nio.charset.StandardCharsets;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;

import io.swagger.v3.oas.annotations.media.Schema;


/**
 * 로그인 요청(API_SPEC §2.5). 클라이언트 종류는 본문이 아니라 {@code X-Client-Type} 헤더로 받는다
 * (§1.2.1) — 이 레코드는 자격 증명만 담는다. JSON 필드명은 전역
 * {@code spring.jackson.property-naming-strategy: SNAKE_CASE}(Ruling 104)가 변환한다.
 *
 * <p>{@code example} 은 시드 상수를 직접 참조한다(BR-113, Ruling 348) — Swagger UI 의
 * "Try it out" 이 기본값 그대로 눌러도 {@code 401} 로 끝나지 않게 한다.
 *
 * <p>비밀번호는 가입과 같은 UTF-8 72바이트 상한을 받는다 — 가입할 수 없는 길이는 맞는 비밀번호일 수 없고, 상한이 없으면 수 MB 본문이
 * BCrypt 대조까지 간다. 아이디는 길이를 자르지 않는다(50자를 넘는 미등록 아이디도 401 이다).
 */
public record LoginRequestPayload(
        @NotBlank @Schema(example = "staffA") String loginId,
        @NotBlank @Schema(example = "password") String password) {

    /** BCrypt 가 받는 72바이트 상한 — 글자 수가 아니라 UTF-8 바이트라 한글은 24자가 상한이다. */
    @AssertTrue
    public boolean isPasswordWithinBcryptLimit() {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}
