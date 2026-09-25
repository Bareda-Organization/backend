package src.backend.location.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/**
 * 위치 송신 요청(API_SPEC §4.12) — {@code lat}·{@code lng}·{@code recorded_at} 는 필수, {@code speed}·
 * {@code heading} 은 선택이다. JSON 필드명은 전역 {@code spring.jackson.property-naming-strategy:
 * SNAKE_CASE}(Ruling 104)가 변환한다. 좌표 범위는 {@code ck_run_position_lat/lng} 와 같다 — 넘긴 값이 DB 까지
 * 가면 제약 위반 {@code 500} 이 된다(BR-032).
 */
public record RunPositionRequest(
        @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal lat,
        @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal lng,
        @NotNull OffsetDateTime recordedAt,
        BigDecimal speed,
        BigDecimal heading) {
}
