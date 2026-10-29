package src.backend.routing.map.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import src.backend.global.location.GeoPoint;
import src.backend.routing.map.spec.RoadLeg;

/**
 * NCP Direction 15 응답을 구간별 {@link RoadLeg} 로 옮기는 순수 변환 모음 — 인접한 같은 지점을 걷어내고
 * 되돌리는 일, 응답 요약을 구간 값으로 나누는 일, 도로 좌표를 첫 구간에 싣는 일을 맡는다.
 */
final class NaverLegParser {

    /** NCP 는 소요 시간을 밀리초로 준다. */
    private static final int MILLIS_PER_SECOND = 1000;

    private NaverLegParser() {
    }

    /** 연속 중복 지점을 걷어낸다 — {@link #expand} 가 반대 방향으로 되돌린다. */
    static List<GeoPoint> withoutConsecutiveDuplicates(List<GeoPoint> segment) {
        List<GeoPoint> distinct = new ArrayList<>();
        distinct.add(segment.getFirst());
        for (int i = 1; i < segment.size(); i++) {
            if (!samePoint(segment.get(i), segment.get(i - 1))) {
                distinct.add(segment.get(i));
            }
        }
        return distinct;
    }

    /**
     * 값이 같은 위경도인지 — {@code BigDecimal.equals} 는 스케일이 다르면 값이 같아도 다르다고
     * 답해 이 판정에 쓰면 안 된다({@code numeric(9,6)} 컬럼끼리는 대개 스케일이 같지만, 그 전제에
     * 기대지 않는다).
     */
    private static boolean samePoint(GeoPoint a, GeoPoint b) {
        return a.lat().compareTo(b.lat()) == 0 && a.lng().compareTo(b.lng()) == 0;
    }

    /** 지점 전부가 한 자리면 부를 이유가 없다 — 거리·시간 0 인 leg 로 바로 답한다. */
    static List<RoadLeg> zeroLegs(int count) {
        return Collections.nCopies(count, new RoadLeg(0, 0, List.of()));
    }

    /**
     * {@link #withoutConsecutiveDuplicates} 로 걷어낸 자리에 거리·시간 0 인 leg 를 끼워 넣어
     * {@code segment.size() - 1} 개로 되돌린다.
     */
    static List<RoadLeg> expand(List<GeoPoint> segment, List<RoadLeg> distinctLegs) {
        List<RoadLeg> legs = new ArrayList<>(segment.size() - 1);
        int distinctIndex = 0;
        for (int i = 1; i < segment.size(); i++) {
            if (samePoint(segment.get(i), segment.get(i - 1))) {
                legs.add(new RoadLeg(0, 0, List.of()));
            } else {
                legs.add(distinctLegs.get(distinctIndex++));
            }
        }
        return legs;
    }

    /**
     * 구간별 실측값이 있으면 그대로 쓰고, 없으면 직선거리 비율로 대체한다.
     *
     * <p>NCP 는 경유지가 있는 요청에 <b>구간별 실측값을 그대로 준다</b>({@code summary.waypoints[]}
     * 가 시작점→첫 경유지, 경유지→다음 경유지 순으로, {@code summary.goal} 이 마지막 경유지→도착지
     * 구간을 담는다(실 API 확인, {@code NaverDirectionsClientLiveTest}).
     * {@link StraightLineLegs#distribute} 는 실측값이 없을 때만 쓰는 직선거리 근사다.
     *
     * <p>경유지가 없어 구간이 하나뿐이면(총합 자체가 그 구간의 실측값이라 배분이 필요 없다) 또는
     * 응답이 예상과 다른 개수를 주면(방어적으로) 기존 직선거리 비율로 되돌아간다.
     */
    static List<RoadLeg> legsOf(List<GeoPoint> segment, NaverDrivingResponse.Summary summary) {
        int expectedLegs = segment.size() - 1;
        List<RoadLeg> measured = measuredLegsOf(summary, expectedLegs);
        if (measured != null) {
            return measured;
        }
        return StraightLineLegs.distribute(segment, (int) Math.round(summary.distance()),
                (int) Math.round(summary.duration() / MILLIS_PER_SECOND));
    }

    /**
     * {@code summary.waypoints[]} + {@code summary.goal} 을 구간 목록으로 옮긴다 — 개수가 안 맞거나
     * 값이 없으면 {@code null} 을 돌려줘 호출부가 직선거리 비율로 대체하게 한다.
     *
     * <p>거리(m)는 이미 정수라 반올림 오차가 없지만, 시간은 밀리초라 구간마다 따로 반올림하면 합이
     * 총합에서 벗어난다({@code 1500ms + 1500ms → 2s + 2s = 4s} 인데 총합은 {@code 3000ms → 3s}).
     * 누적값을 반올림하고 직전에 배정한 값을 빼는 방식({@link StraightLineLegs#distribute} 와 같은
     * 기법)으로 마지막 구간에서 합이 총합과 정확히 맞아떨어지게 한다.
     */
    private static List<RoadLeg> measuredLegsOf(NaverDrivingResponse.Summary summary, int expectedLegs) {
        List<NaverDrivingResponse.WaypointLeg> waypoints = summary.waypoints();
        NaverDrivingResponse.GoalLeg goal = summary.goal();
        if (waypoints == null || waypoints.size() != expectedLegs - 1
                || goal == null || goal.distance() == null || goal.duration() == null) {
            return null;
        }
        List<RoadLeg> legs = new ArrayList<>(expectedLegs);
        CumulativeLegs cumulative = new CumulativeLegs();
        for (NaverDrivingResponse.WaypointLeg waypoint : waypoints) {
            legs.add(cumulative.next(waypoint.distance(), waypoint.duration()));
        }
        legs.add(cumulative.next(goal.distance(), goal.duration()));
        return legs;
    }

    /** 구간값을 누적해 반올림하고 직전 배정값을 뺀 구간 하나씩을 내는 계산기 — 합이 총합과 어긋나지 않게 한다. */
    private static final class CumulativeLegs {

        private double cumulativeMeters;
        private double cumulativeMillis;
        private long assignedMeters;
        private long assignedSeconds;

        /** 다음 구간의 거리(m)·시간(ms)을 누적에 더하고, 그 구간에 배정할 거리·초를 담은 leg 를 낸다. */
        RoadLeg next(double distance, double duration) {
            cumulativeMeters += distance;
            cumulativeMillis += duration;
            long meters = Math.round(cumulativeMeters);
            long seconds = Math.round(cumulativeMillis / MILLIS_PER_SECOND);
            RoadLeg leg = new RoadLeg((int) (meters - assignedMeters), (int) (seconds - assignedSeconds), List.of());
            assignedMeters = meters;
            assignedSeconds = seconds;
            return leg;
        }
    }

    /**
     * 세그먼트 전체 도로 좌표를 <b>첫 leg 에만</b> 싣고 나머지는 비운다.
     *
     * <p>공급자가 경유지별 좌표를 안 주므로(거리·시간처럼 배분할 근거가 없다) leg 마다 나눠 담을
     * 수 없다 — 대신 세그먼트당 좌표 뭉치 하나로 두고, {@link RoadRoute#roadPath()} 가 이어 붙이면서
     * 세그먼트 경계의 겹친 좌표를 걷어낸다. leg 가 비어 있으면(구간 분할이 0개 지점을 만들 수 없어
     * 실제로는 나지 않지만 방어적으로) 그대로 돌려준다.
     */
    static List<RoadLeg> withPath(List<RoadLeg> legs, List<GeoPoint> path) {
        if (legs.isEmpty()) {
            return legs;
        }
        List<RoadLeg> result = new ArrayList<>(legs.size());
        RoadLeg first = legs.getFirst();
        result.add(new RoadLeg(first.distanceMeters(), first.durationSeconds(), path));
        for (int i = 1; i < legs.size(); i++) {
            RoadLeg leg = legs.get(i);
            result.add(new RoadLeg(leg.distanceMeters(), leg.durationSeconds(), List.of()));
        }
        return result;
    }

    /**
     * {@code route.traoptimal[].path} 를 {@link GeoPoint} 로 옮긴다 — <b>NCP 는 좌표를
     * {@code [경도, 위도]} 순으로 준다</b>(NCP 공식 문서 응답 예시 실측). 뒤집어 넣으면 좌표가
     * 지구 반대편(바다)으로 간다.
     */
    static List<GeoPoint> pathOf(NaverDrivingResponse.Traoptimal traoptimal) {
        List<List<BigDecimal>> path = traoptimal.path();
        if (path == null || path.isEmpty()) {
            return List.of();
        }
        List<GeoPoint> points = new ArrayList<>(path.size());
        for (List<BigDecimal> point : path) {
            points.add(new GeoPoint(point.get(1), point.get(0)));
        }
        return points;
    }

    /**
     * 경로를 찾지 못한 응답도 실패로 올린다 — 여기서 예외를 내면 {@link NaverDirectionsGateway} 의
     * {@code fallbackMethod} 를 거쳐 직선거리 근사로 이어지고, 회차 하나가 경로 부재로 통째로 멈추지 않는다.
     *
     * <p>⚠ NCP 는 {@code option} 을 지정하지 않으면 {@code trafast}(최속)가 아니라
     * {@code traoptimal}(기본·실시간 반영)로 응답한다({@code Ruling 276} — 실 호출로 확인).
     * {@code trafast} 를 읽으면 파싱이 항상 {@code null} 로 떨어지고, 그 실패가 예외가 아니라
     * 바로 위 폴백(직선거리 근사)으로 조용히 흡수돼 <b>실 도로 경로가 쓰이지 않는다</b>.
     */
    static NaverDrivingResponse.Traoptimal traoptimalOf(NaverDrivingResponse response) {
        if (response == null || response.route() == null || response.route().traoptimal() == null
                || response.route().traoptimal().isEmpty()) {
            throw new IllegalStateException("네이버 응답에 경로가 없다");
        }
        return response.route().traoptimal().getFirst();
    }
}
