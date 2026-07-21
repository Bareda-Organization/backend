package src.backend.location.dto;

import java.time.LocalDateTime;

/**
 * 버스 위치 조회 응답 — 버스의 최신 좌표에 이름을 덧붙인 뷰(관리자 관제 지도 표시용, F1).
 */
public record BusLocationView(
        Long busId,
        String busName,
        double lat,
        double lng,
        LocalDateTime recordedAt,
        LocationOrigin origin) {

    public static BusLocationView of(String busName, BusLocationPing ping) {
        return new BusLocationView(ping.busId(), busName,
                ping.lat(), ping.lng(), ping.recordedAt(), ping.origin());
    }
}
