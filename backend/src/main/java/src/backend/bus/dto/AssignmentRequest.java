package src.backend.bus.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 배차 변경 요청 — 담당 기사·선탑자·운행 노선 배정. 셋 다 생략 가능(전달된 값만 갱신).
 * 기사·선탑자는 그 학원에서 해당 역할을 가진 계정이어야 한다(BusCommandService 가 검증).
 */
public record AssignmentRequest(
        @Schema(example = "3", description = "담당 기사 user id. 그 학원의 DRIVER 여야 한다") Long driverId,
        @Schema(example = "7", description = "선탑자 user id. 그 학원의 ATTENDANT 여야 한다") Long attendantId,
        @Schema(example = "1", description = "운행 노선 id(하원 A노선)") Long routeId) {
}
