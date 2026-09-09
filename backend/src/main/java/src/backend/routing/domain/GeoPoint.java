package src.backend.routing.domain;

import java.math.BigDecimal;

/**
 * 노선 계산이 다루는 좌표 한 점 — 포트 3종과 파이프라인이 공유하는 유일한 좌표 타입이다.
 *
 * <p>{@code double} 이 아니라 {@link BigDecimal} 인 이유는 ERD 의 좌표 컬럼이 전부
 * {@code numeric(9,6)} 이기 때문이다. {@code double} 로 받으면 저장 시 반올림이 일어나 같은
 * 좌표가 조회에서 안 맞는다.
 */
public record GeoPoint(BigDecimal lat, BigDecimal lng) {

    private static final BigDecimal LAT_MIN = BigDecimal.valueOf(-90);
    private static final BigDecimal LAT_MAX = BigDecimal.valueOf(90);
    private static final BigDecimal LNG_MIN = BigDecimal.valueOf(-180);
    private static final BigDecimal LNG_MAX = BigDecimal.valueOf(180);

    /** 지구 평균 반지름(m). */
    private static final double EARTH_RADIUS_METERS = 6_371_008.8d;

    /**
     * 범위 검사를 여기서 하는 이유는 {@code ck_stop_lat}·{@code ck_stop_lng} 가 DB 에만 있으면
     * 계산 중에는 아무도 막지 않아, 뒤집힌 위경도가 지도 API 호출까지 가서야 드러나기 때문이다.
     */
    public GeoPoint {
        if (lat == null || lng == null) {
            throw new IllegalArgumentException("좌표는 위도·경도가 모두 있어야 한다");
        }
        if (lat.compareTo(LAT_MIN) < 0 || lat.compareTo(LAT_MAX) > 0) {
            throw new IllegalArgumentException("위도가 범위 밖이다: " + lat);
        }
        if (lng.compareTo(LNG_MIN) < 0 || lng.compareTo(LNG_MAX) > 0) {
            throw new IllegalArgumentException("경도가 범위 밖이다: " + lng);
        }
    }

    /**
     * 두 점 사이의 대권 거리(m) — Haversine.
     *
     * <p>승하차지 근접 병합의 {@code StopProximity.metersBetween} 과 <b>일부러 다른 구현</b>이다.
     * 저쪽은 판정 범위가 50m 라 평면 근사가 성립한다고 자기 주석에 근거를 적었고, 노선은 km 규모라
     * 그 근거가 성립하지 않는다. <b>재는 대상이 다르므로 한쪽으로 통일하지 않는다</b> — 두 값을
     * 서로 비교하는 자리가 부재해 갈릴 위험도 부재하다.
     */
    public double distanceMetersTo(GeoPoint other) {
        double lat1 = Math.toRadians(lat.doubleValue());
        double lat2 = Math.toRadians(other.lat.doubleValue());
        double deltaLat = lat2 - lat1;
        double deltaLng = Math.toRadians(other.lng.doubleValue() - lng.doubleValue());

        double a = Math.pow(Math.sin(deltaLat / 2), 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.pow(Math.sin(deltaLng / 2), 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1d, Math.sqrt(a)));
    }
}
