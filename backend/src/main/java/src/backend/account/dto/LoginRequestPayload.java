package src.backend.account.dto;

import jakarta.validation.constraints.NotBlank;

import io.swagger.v3.oas.annotations.media.Schema;

import src.backend.global.common.SeedFixtures;

/**
 * 로그인 요청(API_SPEC §2.5). 클라이언트 종류는 본문이 아니라 {@code X-Client-Type} 헤더로 받는다
 * (§1.2.1) — 이 레코드는 자격 증명만 담는다. JSON 필드명은 전역
 * {@code spring.jackson.property-naming-strategy: SNAKE_CASE}(Ruling 104)가 변환한다.
 *
 * <p>{@code example} 은 시드 상수를 직접 참조한다(BR-113, Ruling 348) — Swagger UI 의
 * "Try it out" 이 기본값 그대로 눌러도 {@code 401} 로 끝나지 않게 한다.
 */
public record LoginRequestPayload(
        @NotBlank @Schema(example = SeedFixtures.STAFF_A_LOGIN_ID) String loginId,
        @NotBlank @Schema(example = SeedFixtures.LOCAL_DEFAULT_PASSWORD) String password) {
}
