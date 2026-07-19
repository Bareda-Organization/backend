package src.backend.routing.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;
import src.backend.routing.domain.RouteDirection;

/** 노선 계획 생성 요청 — serviceDate 생략 시 오늘 기준으로 당일 활성 로스터(결석 제외)를 사용한다. */
public record GenerateRoutePlanRequest(
        @NotNull Long busId,
        @NotNull RouteDirection direction,
        LocalDate serviceDate) {
}
