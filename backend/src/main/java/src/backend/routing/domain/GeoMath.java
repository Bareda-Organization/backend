package src.backend.routing.domain;

/** 위경도 좌표 기반 거리·방위각 순수 계산 — 외부 호출 없음. */
public final class GeoMath {

    private static final double EARTH_RADIUS_M = 6371000;

    private GeoMath() {
    }

    /** 두 좌표 간 대권거리(Haversine, 미터). */
    public static double distanceMeters(LatLng a, LatLng b) {
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLng = Math.toRadians(b.lng() - a.lng());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(a.lat())) * Math.cos(Math.toRadians(b.lat()))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(h));
    }

    /** from → to 방향 초기 방위각(도, 0~360 — 0=정북, 90=정동). sweep 정렬의 결정적 초기 순서 확보에 쓴다. */
    public static double bearingDegrees(LatLng from, LatLng to) {
        double lat1 = Math.toRadians(from.lat());
        double lat2 = Math.toRadians(to.lat());
        double dLng = Math.toRadians(to.lng() - from.lng());
        double y = Math.sin(dLng) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLng);
        double bearing = Math.toDegrees(Math.atan2(y, x));
        return (bearing + 360) % 360;
    }
}
