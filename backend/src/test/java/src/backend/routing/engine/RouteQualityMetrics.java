package src.backend.routing.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import src.backend.global.common.enums.Direction;
import src.backend.routing.domain.GeoPoint;
import src.backend.routing.engine.spec.OrderableStop;
import src.backend.routing.engine.spec.OrderedStop;
import src.backend.routing.engine.spec.RouteOrderInput;
import src.backend.routing.engine.spec.StopOrder;

/**
 * 산출 순서 하나를 지표 3종으로 옮긴다 — 총 주행거리 · 최대 학생 탑승시간 · 정차 수
 * (TECH_DECISIONS §8.5.2).
 *
 * <p><b>최대 학생 탑승시간이 셋 중 대체 불가한 지표다.</b> 총 주행거리만 보면 한 학생을 끝까지
 * 태우고 도는 해가 이긴다 — 버스가 짧게 도는 것과 아이가 짧게 앉아 있는 것은 다른 값이고, 이
 * 서비스에서 나빠지면 곤란한 것은 뒤쪽이다.
 *
 * <p>속도·정차 체류는 <b>이 판정에서만 쓰는 가정값</b>이지 사양의 정책 상수가 아니다(§7 규칙 10과
 * 무관). 지표의 절대값이 아니라 두 순서의 <b>대소</b>만 쓰기 때문에 값 자체는 판정을 바꾸지 않는다.
 * 지도 API 를 부르지 않는 것도 같은 이유다 — 요금·레이트리밋·응답 편차가 판정에 섞이면 그 초록이
 * 코드에 대해 아무것도 말하지 않는다.
 */
final class RouteQualityMetrics {

    /** 통학버스 도심 평균 주행 속도 가정값(km/h). */
    private static final double AVERAGE_SPEED_KMH = 25d;
    /** 정차 1회당 승하차 체류 가정값(초). */
    private static final double DWELL_SECONDS_PER_STOP = 40d;
    private static final double METERS_PER_KM = 1_000d;
    private static final double SECONDS_PER_HOUR = 3_600d;
    private static final double SECONDS_PER_MINUTE = 60d;

    private final double totalDistanceMeters;
    private final double maxRideMinutes;
    private final int stopCount;

    private RouteQualityMetrics(double totalDistanceMeters, double maxRideMinutes, int stopCount) {
        this.totalDistanceMeters = totalDistanceMeters;
        this.maxRideMinutes = maxRideMinutes;
        this.stopCount = stopCount;
    }

    static RouteQualityMetrics of(RouteOrderInput input, StopOrder order) {
        List<GeoPoint> path = pathOf(input, order);
        double[] legMeters = legMetersOf(path);
        return new RouteQualityMetrics(
                totalOf(legMeters),
                maxRideMinutesOf(input, order, legMeters),
                order.stopCount());
    }

    /** 버스가 실제로 달린 거리 합(m) — 출발지에서 도착지까지 전 구간이다. */
    double totalDistanceMeters() {
        return totalDistanceMeters;
    }

    /**
     * 한 학생이 버스에 앉아 있는 가장 긴 시간(분).
     *
     * <p><b>합이 아니라 최댓값이다.</b> 합을 재면 여럿을 조금씩 태우는 해와 한 명을 끝까지 태우는
     * 해가 같은 점수를 받아, 이 지표를 둔 이유가 통째로 사라진다.
     */
    double maxRideMinutes() {
        return maxRideMinutes;
    }

    /** 정차 수 — 승하차지가 누락되거나 중복되면 여기서 드러난다. */
    int stopCount() {
        return stopCount;
    }

    private static List<GeoPoint> pathOf(RouteOrderInput input, StopOrder order) {
        List<GeoPoint> path = new ArrayList<>();
        path.add(input.origin());
        order.sequence().forEach(stop -> path.add(stop.point()));
        path.add(input.destination());
        return path;
    }

    private static double[] legMetersOf(List<GeoPoint> path) {
        double[] legs = new double[path.size() - 1];
        for (int index = 0; index < legs.length; index++) {
            legs[index] = path.get(index).distanceMetersTo(path.get(index + 1));
        }
        return legs;
    }

    private static double totalOf(double[] legs) {
        double sum = 0d;
        for (double leg : legs) {
            sum += leg;
        }
        return sum;
    }

    /**
     * 탑승 인원이 있는 자리마다 탑승 시간을 재고 그중 <b>가장 긴 것</b>을 고른다.
     *
     * <p>등원은 승차한 자리부터 학원까지, 하원은 학원에서 하차하는 자리까지가 탑승 구간이다.
     */
    private static double maxRideMinutesOf(
            RouteOrderInput input, StopOrder order, double[] legMeters) {
        Map<Long, Integer> ridersByStopId = ridersByStopId(input);
        int lastIndex = order.stopCount();
        double longest = 0d;
        for (int position = 1; position <= lastIndex; position++) {
            OrderedStop stop = order.sequence().get(position - 1);
            if (ridersByStopId.getOrDefault(stop.stopId(), 0) == 0) {
                continue;
            }
            longest = Math.max(longest, rideMinutes(input.direction(), position, lastIndex, legMeters));
        }
        return longest;
    }

    private static double rideMinutes(
            Direction direction, int position, int lastIndex, double[] legMeters) {
        boolean toAcademy = direction == Direction.TO_ACADEMY;
        int fromLeg = toAcademy ? position : 0;
        int toLegExclusive = toAcademy ? legMeters.length : position;
        int dwellCount = toAcademy ? lastIndex - position : position - 1;

        double meters = 0d;
        for (int leg = fromLeg; leg < toLegExclusive; leg++) {
            meters += legMeters[leg];
        }
        double seconds = meters / METERS_PER_KM / AVERAGE_SPEED_KMH * SECONDS_PER_HOUR
                + DWELL_SECONDS_PER_STOP * dwellCount;
        return seconds / SECONDS_PER_MINUTE;
    }

    private static Map<Long, Integer> ridersByStopId(RouteOrderInput input) {
        Map<Long, Integer> riders = new HashMap<>();
        for (OrderableStop stop : input.stops()) {
            riders.put(stop.stopId(), stop.riderCount());
        }
        return riders;
    }
}
