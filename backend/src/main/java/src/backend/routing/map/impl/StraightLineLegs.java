package src.backend.routing.map.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import src.backend.routing.domain.GeoPoint;
import src.backend.routing.map.spec.RoadLeg;
import src.backend.routing.map.spec.RoadRoute;

/**
 * 직선거리(Haversine)로 구간을 만든다 — 지도 API 폴백({@code TECH_DECISIONS §8})과, 공급자가 총합만
 * 줄 때의 구간별 배분을 함께 맡는다.
 *
 * <p>둘을 한 클래스에 두는 이유는 바뀌는 이유가 하나이기 때문이다 — "직선거리를 어떻게 도로 값으로
 * 옮기는가" 라는 모형 하나가 두 자리를 동시에 정한다.
 */
final class StraightLineLegs {

    /**
     * 폴백 구간의 소요 시간을 내는 <b>직선거리 기준 실효 속도</b>(km/h) — 도로가 직선보다 길다는
     * 사실까지 흡수한 값이라, 우회 계수를 따로 두지 않는다.
     *
     * <p>yml 이 아니라 코드 상수인 것은 이 값이 공급자 제약이 아니라 <b>근사 모형의 가정</b>이기
     * 때문이다(§7 규칙 10). 여기서 나온 시간이 학부모 화면의 도착 예정 시각이 되므로, 운영에서
     * 조용히 바뀌면 같은 노선의 안내 시각이 배포 없이 달라진다.
     */
    private static final int FALLBACK_EFFECTIVE_SPEED_KMH = 20;

    private static final double SECONDS_PER_HOUR = 3600d;

    private static final double METERS_PER_KILOMETER = 1000d;

    private StraightLineLegs() {
    }

    /**
     * 지도 API 를 못 부른 경우의 근사 경로 — {@code fallbackUsed} 가 참인 유일한 생산 지점이다.
     *
     * <p>각 구간의 {@code path} 는 승하차지 좌표 그대로인 두 점(직선)이다(R15 T1 목표 6) — 근사값도
     * 화면에 경로를 그릴 수 있어야 하고, 이웃 구간의 끝점·시작점이 같은 객체이므로
     * {@link RoadRoute#roadPath()} 의 이음매 중복 제거가 그대로 걸린다.
     */
    static RoadRoute approximate(List<GeoPoint> points) {
        List<RoadLeg> legs = new ArrayList<>();
        for (int i = 0; i < points.size() - 1; i++) {
            GeoPoint from = points.get(i);
            GeoPoint to = points.get(i + 1);
            int meters = (int) Math.round(from.distanceMetersTo(to));
            legs.add(new RoadLeg(meters, secondsFor(meters), List.of(from, to)));
        }
        return new RoadRoute(legs, true);
    }

    /**
     * 공급자가 준 구간 총합을 직선거리 비율로 갈라 구간별 값을 만든다 — <b>갈린 값의 합은 총합과
     * 정확히 같다.</b>
     *
     * <p>{@code NaverDirectionsGateway.legsOf(List, Summary)} 가 <b>먼저 구간별 실측값
     * ({@code summary.waypoints[]}·{@code goal})을 시도하고, 그 값이 없을 때만</b> 이 메서드로
     * 넘어온다 — 경유지가 없어 구간이 하나뿐이거나(총합 자체가 그 구간 값이라 배분이 무의미) 응답이
     * 예상과 다른 개수를 줄 때다(2026-09-19 실 API 확인 — NCP 는 경유지가 있으면 구간별 실측값을
     * 그대로 준다). 누적값으로 반올림해 마지막 구간에 잔차를 몰지 않으면, 구간 수가 늘수록 합이
     * 총합에서 벌어져 노선 전체의 거리·소요 시간이 조용히 달라진다.
     *
     * <p>좌표(path)는 여기서 배분하지 않는다 — 이 메서드가 만드는 leg 는 전부 빈 목록이고,
     * {@code NaverDirectionsGateway} 가 이 결과의 첫 leg 에 세그먼트 전체 좌표를 되채운다(거리·시간과
     * 달리 공급자가 경유지 단위 좌표를 주지 않기 때문).
     */
    static List<RoadLeg> distribute(List<GeoPoint> points, int totalMeters, int totalSeconds) {
        double[] straightLine = straightLineDistances(points);
        double sum = 0;
        for (double each : straightLine) {
            sum += each;
        }
        if (sum == 0) {
            sum = straightLine.length;
            Arrays.fill(straightLine, 1d);
        }
        List<RoadLeg> legs = new ArrayList<>(straightLine.length);
        double cumulative = 0;
        long assignedMeters = 0;
        long assignedSeconds = 0;
        for (double each : straightLine) {
            cumulative += each;
            long meters = Math.round(totalMeters * cumulative / sum);
            long seconds = Math.round(totalSeconds * cumulative / sum);
            legs.add(new RoadLeg((int) (meters - assignedMeters), (int) (seconds - assignedSeconds), List.of()));
            assignedMeters = meters;
            assignedSeconds = seconds;
        }
        return legs;
    }

    private static double[] straightLineDistances(List<GeoPoint> points) {
        double[] distances = new double[points.size() - 1];
        for (int i = 0; i < distances.length; i++) {
            distances[i] = points.get(i).distanceMetersTo(points.get(i + 1));
        }
        return distances;
    }

    private static int secondsFor(int meters) {
        return (int) Math.round(meters * SECONDS_PER_HOUR
                / (FALLBACK_EFFECTIVE_SPEED_KMH * METERS_PER_KILOMETER));
    }
}
