package src.backend.location.dto;

import java.time.LocalDateTime;

/**
 * 위치 조회 응답 — 학생의 최신 좌표에 이름을 덧붙인 뷰.
 * 하나의 위치를 학생 본인/학부모/기사/관리자가 각자 권한 범위에서 이 형태로 조회한다.
 */
public record LocationView(
        Long studentId,
        String studentName,
        double lat,
        double lng,
        LocalDateTime recordedAt,
        LocationOrigin origin) {

    public static LocationView of(String studentName, LocationPing ping) {
        return new LocationView(ping.studentId(), studentName,
                ping.lat(), ping.lng(), ping.recordedAt(), ping.origin());
    }
}
