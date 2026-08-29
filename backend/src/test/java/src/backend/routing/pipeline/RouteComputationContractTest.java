package src.backend.routing.pipeline;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Weekday;
import src.backend.routing.domain.GeoPoint;
import src.backend.routing.engine.spec.RouteEngine;
import src.backend.routing.engine.spec.RouteOrderInput;
import src.backend.routing.engine.spec.StopOrder;
import src.backend.routing.engine.spec.OrderedStop;
import src.backend.routing.entity.RouteVersionSource;
import src.backend.routing.map.spec.CallerPolicy;
import src.backend.routing.map.spec.RoadLeg;
import src.backend.routing.map.spec.RoadRoute;

/**
 * 결과 조립이 <b>스스로 막는 것</b>을 고정한다 — 셋 다 어긋나도 값이 그럴듯해 화면·저장 어느 쪽에서도
 * 드러나지 않는 형태다.
 */
class RouteComputationContractTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 3, 2, 8, 0, 0, 0, ZoneOffset.ofHours(9));

    private static final GeoPoint POINT = new GeoPoint(new BigDecimal("37.500000"), new BigDecimal("127.000000"));

    @Test
    @DisplayName("도착 예정 시각이 정차지보다 적으면 결과를 만들 수 없다")
    void rejectsEtaCountMismatch() {
        List<OrderedStop> stops = List.of(
                OrderedStop.ofStop(1L, 1, POINT), OrderedStop.ofStop(2L, 2, POINT));

        assertThatThrownBy(() -> new RouteComputation(stops, 10, new BigDecimal("1.00"),
                List.of(NOW), List.of(), snapshot("heuristic")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("같은 길이");
    }

    @Test
    @DisplayName("엔진 이름이 30자를 넘으면 스냅샷을 만들 수 없다")
    void rejectsOverlongEngineName() {
        assertThatCode(() -> snapshot("a".repeat(30))).doesNotThrowAnyException();

        assertThatThrownBy(() -> snapshot("a".repeat(31)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("엔진 이름은");
    }

    @Test
    @DisplayName("엔진이 파이프라인과 같은 이름의 정책값을 내면 스냅샷 조립을 멈춘다")
    void rejectsPolicySnapshotKeyCollision() {
        RouteComputationPipeline pipeline = new RouteComputationPipeline(
                new DailyStopResolver(null, null), new CollidingEngine(),
                request -> new RoadRoute(List.of(new RoadLeg(1000, 60)), false));

        assertThatThrownBy(() -> pipeline.compute(emptyRosterInput()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("키가 겹친다");
    }

    /** 명단이 비어도 출발지 → 도착지 한 구간은 성립한다 — 이 입력이 위 시험이 저장소를 안 건드리는 근거다. */
    private static RouteComputationInput emptyRosterInput() {
        return new RouteComputationInput(
                DailyRoster.of(1L, Weekday.MON, Direction.TO_ACADEMY, List.of()),
                POINT, POINT, List.of(), NOW,
                new ComputationPolicy(Duration.ofSeconds(5), CallerPolicy.BATCH,
                        RouteVersionSource.CONFIRM_BATCH));
    }

    /** 파이프라인이 쓰는 {@code caller} 키를 엔진이 함께 내보내는 상황을 만든다. */
    private static final class CollidingEngine implements RouteEngine {

        @Override
        public String name() {
            return "colliding";
        }

        @Override
        public Map<String, Object> policySnapshot() {
            return Map.of("caller", "engine-owned");
        }

        @Override
        public StopOrder order(RouteOrderInput input) {
            return new StopOrder(List.of());
        }
    }

    private static ComputationSnapshot snapshot(String engineName) {
        return new ComputationSnapshot(engineName, Map.of("objective", "total_distance"),
                RouteVersionSource.CONFIRM_BATCH, false);
    }
}
