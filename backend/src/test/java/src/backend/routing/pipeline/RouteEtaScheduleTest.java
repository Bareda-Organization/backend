package src.backend.routing.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import src.backend.routing.map.spec.RoadLeg;
import src.backend.routing.map.spec.RoadRoute;

/**
 * ④단계(ETA 산출)를 손으로 만든 구간값으로 검증한다 — 파이프라인 전체를 태우면 구간값이 스텁이
 * 정한 수치라 <b>누적했는지 구간값을 그대로 실었는지</b>가 드러나지 않는다.
 *
 * <p>구간을 60 · 120 · 180초로 <b>서로 다르게</b> 잡은 것이 요점이다. 같은 값으로 두면 누적을 빠뜨린
 * 구현도 등차 수열을 내어 "시각이 순서대로 늘어난다" 는 단언을 통과한다.
 */
class RouteEtaScheduleTest {

    private static final OffsetDateTime DEPART_AT =
            OffsetDateTime.of(2026, 3, 2, 8, 0, 0, 0, ZoneOffset.ofHours(9));

    @Test
    @DisplayName("정차지 도착 예정 시각은 구간 소요를 누적한 값이다")
    void accumulatesLegDurations() {
        RoadRoute road = new RoadRoute(
                List.of(new RoadLeg(1000, 60), new RoadLeg(2000, 120), new RoadLeg(3000, 180)), false);

        RouteEtaSchedule schedule = RouteEtaSchedule.accumulate(road, DEPART_AT, 2);

        assertThat(schedule.etas()).containsExactly(
                DEPART_AT.plusSeconds(60), DEPART_AT.plusSeconds(180));
    }

    @Test
    @DisplayName("총 소요는 도착지 구간까지 더하고 분 단위로 올린다")
    void roundsTotalDurationUp() {
        RoadRoute road = new RoadRoute(List.of(new RoadLeg(500, 31), new RoadLeg(500, 30)), false);

        RouteEtaSchedule schedule = RouteEtaSchedule.accumulate(road, DEPART_AT, 1);

        assertThat(schedule.estDurationMin()).isEqualTo(2);
    }

    @Test
    @DisplayName("총 주행거리는 소수 2자리 km 다")
    void reportsDistanceWithTwoDecimals() {
        RoadRoute road = new RoadRoute(List.of(new RoadLeg(1234, 60), new RoadLeg(1111, 60)), false);

        RouteEtaSchedule schedule = RouteEtaSchedule.accumulate(road, DEPART_AT, 1);

        assertThat(schedule.estDistanceKm()).isEqualByComparingTo(new BigDecimal("2.35"));
        assertThat(schedule.estDistanceKm().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("구간 수가 정차지 수 + 1 이 아니면 조립을 멈춘다")
    void rejectsLegCountMismatch() {
        RoadRoute road = new RoadRoute(List.of(new RoadLeg(1000, 60)), false);

        assertThatThrownBy(() -> RouteEtaSchedule.accumulate(road, DEPART_AT, 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("구간 수가 정차지 수 + 1 이 아니다");
    }
}
