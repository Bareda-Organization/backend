package src.backend.drivesession.entity;

/** 운행 세션 상태 — 시작 즉시 IN_PROGRESS, 종료하면 COMPLETED(단방향, 재개 없음). */
public enum DriveSessionStatus {
    IN_PROGRESS,
    COMPLETED
}
