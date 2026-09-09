package src.backend.student.domain;

import java.math.BigDecimal;

/**
 * 승하차지 근접 병합의 임계 거리와 거리 계산(STU-05) — "같은 곳에 서는가" 를 판정하는 유일한 자리다.
 *
 * <p>임계를 코드 상수로 두는 것은 §7 규칙 10 이다. yml 로 빼면 운영에서 이 값이 조용히 바뀌어,
 * 노선이 왜 달라졌는지 코드 어디를 봐도 알 수 없게 된다.
 */
public final class StopProximity {

    /**
     * 이 거리 안의 두 주소는 <b>한 승하차지</b>로 묶인다.
     *
     * <p>정본이 값을 규정하지 않아 여기서 정한다. 50m 는 <b>버스가 한 번 서서 함께 태울 수 있는
     * 폭</b>이다 — 같은 아파트 단지의 동 주소들은 지오코딩이 수십 m 씩 벌어진 점으로 돌려주므로 그
     * 폭을 담아야 하고, 반대로 왕복 2차로를 사이에 둔 건너편(대개 100m 이상)까지 묶으면 버스가 설 수
     * 없는 지점이 승하차지가 된다.
     */
    public static final int MERGE_RADIUS_METERS = 50;

    /** 위도 1도의 미터 환산 — 지구 자오선 둘레 / 360. */
    private static final double METERS_PER_DEGREE_LATITUDE = 111_320d;

    private StopProximity() {
    }

    /**
     * 두 좌표 사이의 대략 거리(m) — 평면 근사다.
     *
     * <p>구면 공식(haversine)을 쓰지 않는 이유는 판정 범위가 {@value #MERGE_RADIUS_METERS}m 라
     * 곡률 오차가 밀리미터 단위이기 때문이다. 근사가 무너지는 것은 수백 km 규모이고 이 판정은 그
     * 규모에 닿지 않는다.
     */
    public static double metersBetween(BigDecimal lat1, BigDecimal lng1, BigDecimal lat2, BigDecimal lng2) {
        double meanLatitude = Math.toRadians((lat1.doubleValue() + lat2.doubleValue()) / 2);
        double northSouth = (lat1.doubleValue() - lat2.doubleValue()) * METERS_PER_DEGREE_LATITUDE;
        double eastWest = (lng1.doubleValue() - lng2.doubleValue())
                * METERS_PER_DEGREE_LATITUDE * Math.cos(meanLatitude);
        return Math.hypot(northSouth, eastWest);
    }

    /**
     * 임계 거리를 담는 <b>정사각형</b>의 반변 길이(도) — 후보를 인덱스로 좁힐 때 쓴다.
     *
     * <p>경도 기준으로 환산해 위·경도에 같은 값을 쓴다. 경도 1도는 위도 1도보다 <b>짧으므로</b>
     * (위도 37도에서 약 8할) 이 값을 위도에도 그대로 쓰면 남북으로 더 넓은 상자가 된다 — 상자는
     * 원의 <b>초과집합</b>이어야 후보를 빠뜨리지 않는다. 반대로 위도 기준으로 환산하면 동서가
     * 좁아져 임계 안의 승하차지를 놓친다.
     */
    public static BigDecimal searchBoxDegrees(BigDecimal lat) {
        double metersPerDegreeLongitude = METERS_PER_DEGREE_LATITUDE * Math.cos(Math.toRadians(lat.doubleValue()));
        return BigDecimal.valueOf(MERGE_RADIUS_METERS / metersPerDegreeLongitude);
    }
}
