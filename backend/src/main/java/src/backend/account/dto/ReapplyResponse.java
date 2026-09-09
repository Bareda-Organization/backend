package src.backend.account.dto;

import java.time.OffsetDateTime;

/**
 * 재신청 응답(API_SPEC §2.4) — 재신청 직후는 항상 {@code pending}. JSON 필드명은 전역
 * {@code spring.jackson.property-naming-strategy: SNAKE_CASE}(Ruling 104)가 변환한다.
 */
public record ReapplyResponse(String status, OffsetDateTime requestedAt) {
}
