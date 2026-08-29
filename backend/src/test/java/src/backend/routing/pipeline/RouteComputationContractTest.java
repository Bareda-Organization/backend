package src.backend.routing.pipeline;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import src.backend.routing.domain.GeoPoint;
import src.backend.routing.engine.spec.OrderedStop;
import src.backend.routing.entity.RouteVersionSource;

/**
 * 결과 타입이 <b>스스로 막는 것</b>을 고정한다 — 둘 다 어긋나도 값이 그럴듯해 화면·저장 어느 쪽에서도
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

    private static ComputationSnapshot snapshot(String engineName) {
        return new ComputationSnapshot(engineName, Map.of("objective", "total_distance"),
                RouteVersionSource.CONFIRM_BATCH, false);
    }
}
