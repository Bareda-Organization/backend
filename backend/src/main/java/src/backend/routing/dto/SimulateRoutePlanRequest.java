package src.backend.routing.dto;

import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import src.backend.routing.domain.RouteDirection;

/** 배차 변경안 시뮬레이션 요청 — serviceDate 생략 시 오늘. overrides 가 비면 현재 로스터를 그대로 재계산한다. */
public record SimulateRoutePlanRequest(
        @NotNull @Schema(example = "1", description = "버스 id(1=3호차, 학생 1·2·3 배정)") Long busId,
        @NotNull @Schema(example = "DROPOFF", description = "PICKUP=등원(승차지 기준), DROPOFF=하원(하차지 기준)") RouteDirection direction,
        @Schema(example = "2026-08-02", description = "대상 날짜. 생략하면 오늘 — 승인된 결석 신고가 있는 학생은 이 날짜 기준으로 빠진다") LocalDate serviceDate,
        @Valid @Schema(description = "변경안 목록. 비우거나 생략하면 현재 명단을 그대로 재계산한다(= 지금 노선의 재확인). "
                + "변경안 적용 후 정차가 0개가 되면 400 이다") List<StudentOverride> overrides) {
}
