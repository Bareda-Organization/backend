package src.backend.drivesession.dto;

/**
 * 운행 세션 명단 1건 — 이름·위치만(사진은 {@code Student}에 아직 필드가 없어 제외,
 * 추후 StorageService 포트 도입 시 함께 확장).
 */
public record DriveSessionRosterEntry(Long studentId, String name, String location, Double lat, Double lng) {
}
