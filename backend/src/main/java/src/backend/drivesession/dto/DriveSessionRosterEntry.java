package src.backend.drivesession.dto;

/**
 * 운행 세션 명단 1건 — 이름·사진·승하차 위치. 선탑자 앱의 인물 카드가 그대로 소비한다.
 */
public record DriveSessionRosterEntry(Long studentId, String name, String photoUrl,
                                      String location, Double lat, Double lng) {
}
