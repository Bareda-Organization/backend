package src.backend.bus.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 배차 변경 요청 — 담당 기사·운행 노선 배정. 둘 다 생략 가능(전달된 값만 갱신).
 */
public record AssignmentRequest(
        @Schema(example = "3", description = "담당 기사 user id(박기사)") Long driverId,
        @Schema(example = "1", description = "운행 노선 id(하원 A노선)") Long routeId) {
}
