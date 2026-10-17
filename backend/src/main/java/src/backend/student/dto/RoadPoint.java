package src.backend.student.dto;

import java.math.BigDecimal;

/**
 * 학생 노선 응답의 도로 좌표 한 점 — JSON 모양은 {@code routing} 의 좌표 타입과 같은 {@code {lat, lng}} 이다. 모듈 간 참조를 끊으려고
 * 학생 쪽이 따로 가진다(AR-4).
 */
public record RoadPoint(BigDecimal lat, BigDecimal lng) {

    /** 지구 평균 반지름(m). */
    private static final double EARTH_RADIUS_METERS = 6_371_008.8d;

    /** 두 점 사이의 대권 거리(m) — Haversine. {@code routing} 의 좌표 타입과 같은 식이라 가까운 점 판정이 갈리지 않는다. */
    public double distanceMetersTo(RoadPoint other) {
        double lat1 = Math.toRadians(lat.doubleValue());
        double lat2 = Math.toRadians(other.lat.doubleValue());
        double deltaLat = lat2 - lat1;
        double deltaLng = Math.toRadians(other.lng.doubleValue() - lng.doubleValue());

        double a = Math.pow(Math.sin(deltaLat / 2), 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.pow(Math.sin(deltaLng / 2), 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1d, Math.sqrt(a)));
    }
}
