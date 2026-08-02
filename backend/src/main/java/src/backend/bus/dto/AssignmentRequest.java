package src.backend.bus.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 배차 변경 요청 — 담당 기사·선탑자·운행 노선 배정. 셋 다 생략 가능(전달된 값만 갱신).
 * 기사·선탑자는 그 학원에서 해당 역할을 가진 계정이어야 한다(BusCommandService 가 검증).
 */
public record AssignmentRequest(
        @Schema(example = "3", description = "담당 기사 user id(박기사). 그 학원의 DRIVER 여야 한다") Long driverId,
        // ⚠️ 예시는 7(윤선탑)이 아니라 6(최선탑)이다 — 7은 시드에서 1호차 선탑자라
        //    버스 1에 넣으면 "이미 다른 버스에 배정" 409 가 난다(I-6). 예시는 그대로 실행해도 성공해야 한다.
        @Schema(example = "6", description = "선탑자 user id(최선탑, 3호차 현재 선탑자). 그 학원의 ATTENDANT 여야 하고, "
                + "같은 학원의 다른 버스에 이미 배정돼 있으면 409") Long attendantId,
        @Schema(example = "1", description = "운행 노선 id(하원 A노선)") Long routeId) {
}
