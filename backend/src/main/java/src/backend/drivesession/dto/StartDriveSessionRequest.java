package src.backend.drivesession.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;
import src.backend.routing.domain.RouteDirection;

/** 운행 시작 요청 — serviceDate 생략 시 오늘 기준. */
public record StartDriveSessionRequest(
        @NotNull Long busId,
        @NotNull RouteDirection direction,
        LocalDate serviceDate) {
}
