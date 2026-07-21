package src.backend.drivesession.dto;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import src.backend.routing.domain.RouteDirection;

/** 운행 시작 요청 — serviceDate 생략 시 오늘 기준. */
public record StartDriveSessionRequest(
        @NotNull @Schema(example = "1", description = "버스 id(3호차)") Long busId,
        @NotNull @Schema(example = "DROPOFF") RouteDirection direction,
        @Schema(example = "2026-07-20") LocalDate serviceDate) {
}
