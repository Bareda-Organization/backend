package src.backend.routing.pipeline;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import src.backend.routing.map.spec.RoadLeg;
import src.backend.routing.map.spec.RoadRoute;

/**
 * 노선 계산 ④단계 — 구간 소요를 누적해 정차지별 도착 예정 시각과 총 소요 · 총 거리를 낸다
 * ({@code ARCHITECTURE §8.2} ④).
 *
 * <p><b>정차 체류 시간을 더하지 않는다.</b> 정본이 "정차 시간 고려" 를 적으면서도 그 가중치 기준을
 * 보류했고(PRD §5.1 4단계 · §10.1 G), 값이 정해지지 않은 채로 상수를 지어 누적하면 도착 예정 시각이
 * 어디서 온 값인지 사후에 설명할 수단이 부재해진다. 대신 0초를 썼다는 사실을 계산 스냅샷에
 * {@code dwellSecondsPerStop} 으로 남겨(TECH_DECISIONS §8.5.1), 기준이 정해진 뒤에도 과거 산출이
 * 어떤 가정 위에 있었는지 재현 가능하게 한다.
 *
 * @param etas           정차지별 도착 예정 시각
 * @param estDurationMin 출발부터 도착지까지의 총 소요(분)
 * @param estDistanceKm  총 주행거리(km, 소수 2자리)
 */
record RouteEtaSchedule(List<OffsetDateTime> etas, int estDurationMin, BigDecimal estDistanceKm) {

    /** 정차지 한 곳당 체류로 잡는 시간(초) — 위 주석의 근거로 0이며, 스냅샷에 그대로 실린다. */
    static final int DWELL_SECONDS_PER_STOP = 0;

    /** {@code route_version.est_distance_km} 가 {@code numeric(6,2)} 라 계산 단계에서 자릿수를 맞춘다. */
    private static final int DISTANCE_SCALE = 2;

    private static final BigDecimal METERS_PER_KILOMETER = BigDecimal.valueOf(1000);

    private static final int SECONDS_PER_MINUTE = 60;

    /**
     * 구간을 순서대로 누적한다 — {@code legs} 는 <b>출발지 → 정차지들 → 도착지</b> 순이라
     * {@code stopCount + 1} 개다.
     *
     * <p>길이를 여기서 확인하는 이유는 어긋남이 <b>조용하기</b> 때문이다. 구간이 하나 모자라면 마지막
     * 정차지의 시각이 안 나오고, 하나 많으면 도착지 구간이 정차지에 붙어 실제보다 늦은 시각이
     * 나가는데, 둘 다 값이 그럴듯해 화면에서 구별되지 않는다.
     */
    static RouteEtaSchedule accumulate(RoadRoute road, OffsetDateTime departAt, int stopCount) {
        List<RoadLeg> legs = road.legs();
        if (legs.size() != stopCount + 1) {
            throw new IllegalStateException(
                    "구간 수가 정차지 수 + 1 이 아니다: 정차지 " + stopCount + " · 구간 " + legs.size());
        }
        List<OffsetDateTime> etas = new ArrayList<>(stopCount);
        long seconds = 0;
        long meters = 0;
        for (int index = 0; index < legs.size(); index++) {
            seconds += legs.get(index).durationSeconds();
            meters += legs.get(index).distanceMeters();
            if (index < stopCount) {
                etas.add(departAt.plusSeconds(seconds));
            }
        }
        return new RouteEtaSchedule(etas, minutesOf(seconds), kilometersOf(meters));
    }

    /**
     * 분 단위로 <b>올린다</b> — 내리면 동승자 근무 시간 충돌 판정(MGR-05·06)이 실제보다 짧은 운행으로
     * 통과해, 근무 시간이 끝난 뒤까지 도는 회차에 사람이 붙는다.
     */
    private static int minutesOf(long seconds) {
        return Math.toIntExact((seconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE);
    }

    private static BigDecimal kilometersOf(long meters) {
        return BigDecimal.valueOf(meters).divide(METERS_PER_KILOMETER, DISTANCE_SCALE, RoundingMode.HALF_UP);
    }
}
