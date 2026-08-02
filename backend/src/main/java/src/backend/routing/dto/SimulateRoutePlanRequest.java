package src.backend.routing.dto;

import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import src.backend.routing.domain.RouteDirection;

/** 배차 변경안 시뮬레이션 요청 — serviceDate 생략 시 오늘. overrides 가 비면 현재 로스터를 그대로 재계산한다. */
public record SimulateRoutePlanRequest(
        @NotNull @Schema(example = "1", description = "버스 id") Long busId,
        @NotNull @Schema(example = "DROPOFF") RouteDirection direction,
        @Schema(example = "2026-08-02") LocalDate serviceDate,
        @Valid List<StudentOverride> overrides) {
}
