package src.backend.routing.dto;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import src.backend.routing.domain.RouteDirection;

/** 멀티버스 자동 배정(제안) 요청 — serviceDate 생략 시 오늘 기준. */
public record AutoAssignRequest(
        @NotNull @Schema(example = "1", description = "학원 id(한빛학원)") Long tenantId,
        @NotNull @Schema(example = "PICKUP") RouteDirection direction,
        @Schema(example = "2026-07-21") LocalDate serviceDate) {
}
