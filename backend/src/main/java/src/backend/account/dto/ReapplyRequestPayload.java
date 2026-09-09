package src.backend.account.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 재신청 요청(API_SPEC §2.4) — 학원을 다시 선택한다. JSON 필드명은 전역
 * {@code spring.jackson.property-naming-strategy: SNAKE_CASE}(Ruling 104)가 변환한다.
 */
public record ReapplyRequestPayload(@NotBlank String academyId) {
}
