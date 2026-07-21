package src.backend.routing.dto;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import src.backend.routing.domain.RouteDirection;

/** 노선 계획 생성 요청 — serviceDate 생략 시 오늘 기준으로 당일 활성 로스터(결석 제외)를 사용한다. */
public record GenerateRoutePlanRequest(
        @NotNull @Schema(example = "1", description = "버스 id(3호차)") Long busId,
        @NotNull @Schema(example = "DROPOFF") RouteDirection direction,
        @Schema(example = "2026-07-20") LocalDate serviceDate) {
}
