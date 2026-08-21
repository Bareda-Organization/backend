package src.backend.operations.dto;

/**
 * 버스 1대의 오늘 운행 진행 상태 — {@code DriveSessionStatus}(IN_PROGRESS·COMPLETED)에 세션이
 * 아예 없는 경우(NOT_STARTED)를 더한 화면 전용 3단계다. 세션 엔티티 자체에 이 값을 추가하지 않는
 * 이유: "세션 없음"은 세션의 상태가 아니라 세션의 부재이고, 관제 목록만 이 구분이 필요하다.
 */
public enum BusSessionStatus {
    NOT_STARTED,
    IN_PROGRESS,
    COMPLETED
}
