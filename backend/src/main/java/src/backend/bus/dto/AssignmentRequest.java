package src.backend.bus.dto;

/**
 * 배차 변경 요청 — 담당 기사·운행 노선 배정. 둘 다 생략 가능(전달된 값만 갱신).
 */
public record AssignmentRequest(
        Long driverId,
        Long routeId) {
}
