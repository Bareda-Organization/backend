package src.backend.account.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 로그인 요청(API_SPEC §2.5). 클라이언트 종류는 본문이 아니라 {@code X-Client-Type} 헤더로 받는다
 * (§1.2.1) — 이 레코드는 자격 증명만 담는다. JSON 필드명은 전역
 * {@code spring.jackson.property-naming-strategy: SNAKE_CASE}(Ruling 104)가 변환한다.
 */
public record LoginRequestPayload(@NotBlank String loginId, @NotBlank String password) {
}
