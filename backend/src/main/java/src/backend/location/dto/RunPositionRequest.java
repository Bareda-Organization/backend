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
 *
 * <p>{@code speed}(km/h)·{@code heading}(도) 범위는 저장 컬럼 {@code numeric(5,2)} 와 방위 정의에서 온다 —
 * 밖의 값을 받으면 저장 단계에서 {@code 500} 으로 새므로 입구에서 {@code 422} 로 막는다(BR-115).
 *
 * <p>{@code recorded_at} 은 서버 수신 시각과 5분 넘게 어긋나면 {@code 422} 다(BR-243) — 시각 비교에 서버 시계가 필요해 요청 검증이 아니라 {@code RunPositionCommandService} 가 본다.
 */
public record RunPositionRequest(
        @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal lat,
        @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal lng,
        @NotNull OffsetDateTime recordedAt,
        @DecimalMin("0") @DecimalMax("999.99") BigDecimal speed,
        @DecimalMin("0") @DecimalMax("360") BigDecimal heading) {
}
