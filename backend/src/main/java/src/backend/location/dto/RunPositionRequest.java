package src.backend.location.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import jakarta.validation.constraints.NotNull;

/**
 * 위치 송신 요청(API_SPEC §4.12) — {@code lat}·{@code lng}·{@code recorded_at} 는 필수, {@code speed}·
 * {@code heading} 은 선택이다. JSON 필드명은 전역 {@code spring.jackson.property-naming-strategy:
 * SNAKE_CASE}(Ruling 104)가 변환한다.
 */
public record RunPositionRequest(
        @NotNull BigDecimal lat,
        @NotNull BigDecimal lng,
        @NotNull OffsetDateTime recordedAt,
        BigDecimal speed,
        BigDecimal heading) {
}
