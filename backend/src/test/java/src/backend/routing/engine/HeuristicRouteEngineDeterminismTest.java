package src.backend.routing.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import src.backend.global.common.enums.Direction;
import src.backend.routing.domain.GeoPoint;
import src.backend.routing.engine.impl.HeuristicRouteEngine;
import src.backend.routing.engine.spec.OrderableStop;
import src.backend.routing.engine.spec.RouteEngine;
import src.backend.routing.engine.spec.RouteOrderInput;
import src.backend.routing.engine.spec.StopOrder;

/**
 * 같은 입력이 <b>언제나</b> 같은 순서를 낸다는 것을 고정한다.
 *
 * <p>품질 회귀 판정(TECH_DECISIONS §8.5.2)이 성립하기 위한 선행 조건이라 따로 둔다 — 산출이
 * 실행마다 갈리면 기록해 둔 상한이 무엇을 재는 값인지 알 수 없고, 그러면 그 초록도 빨강도 코드에
 * 대해 아무것도 말하지 않는다.
 *
 * <h2>동점 좌표를 손으로 만든 이유</h2>
 *
 * <p>데이터셋 3종에는 <b>거리가 정확히 같은 후보가 없다.</b> 그래서 데이터셋만으로 섞기 시험을
 * 돌리면 동점 규칙을 통째로 지워도 초록이다(음성 대조에서 실측).
 *
 * <p>동점을 만드는 것만으로도 부족했다 — 2-opt 가 씨앗의 선택을 되돌려 놓아 최종 산출이 같아지고,
 * 그러면 동점 규칙을 지워도 여전히 초록이다(이것도 음성 대조에서 실측). 그래서 아래 좌표는
 * <b>두 순서의 총 주행거리까지 정확히 같도록</b> 출발지–도착지 축에 대칭으로 놓았다. 2-opt 의
 * 이득이 0 이라 되돌릴 수가 없고, 남는 것은 씨앗이 고른 순서뿐이다.
 *
 * <p>경도 차만 부호가 반대이고 위도 쌍이 같아, {@code sin²} 이 부호를 지우면서 두 거리가
 * <b>비트 단위로</b> 같아진다.
 */
class HeuristicRouteEngineDeterminismTest {

    /** 대칭축은 경도 127.000000 — 출발지와 도착지가 이 선 위에 있다. */
    private static final GeoPoint TIE_ORIGIN = point("37.500000", "127.000000");
    private static final GeoPoint TIE_DESTINATION = point("37.504000", "127.000000");
    private static final OrderableStop TIED_WEST =
            new OrderableStop(21L, point("37.502000", "126.998000"), 1);
    private static final OrderableStop TIED_EAST =
            new OrderableStop(22L, point("37.502000", "127.002000"), 1);

    private final RouteEngine engine = new HeuristicRouteEngine();

    static List<String> datasets() {
        return QualityDataset.allNames();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("datasets")
    void 같은_입력을_두_번_계산하면_같은_순서가_나온다(String name) {
        RouteOrderInput input = QualityDataset.load(name).input();

        assertThat(seqOf(engine.order(input)))
                .as("%s — 실행마다 갈리면 기록된 상한이 무엇을 재는 값인지 알 수 없다", name)
                .isEqualTo(seqOf(engine.order(input)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("datasets")
    void 입력_목록의_나열_순서를_섞어도_같은_순서가_나온다(String name) {
        RouteOrderInput input = QualityDataset.load(name).input();
        List<OrderableStop> shuffled = new ArrayList<>(input.stops());
        Collections.shuffle(shuffled, new Random(20260829L));

        assertThat(seqOf(engine.order(withStops(input, shuffled))))
                .as("%s — 나열 순서에 기대면 명단 조회 순서가 바뀔 때 노선이 조용히 바뀐다", name)
                .isEqualTo(seqOf(engine.order(input)));
    }

    @Test
    void 거리가_같은_두_후보_중_승하차지_ID_가_작은_쪽을_먼저_고른다() {
        StopOrder order = engine.order(tieInput(List.of(TIED_WEST, TIED_EAST)));

        assertThat(order.sequence().getFirst().stopId())
                .as("동점을 값으로 가르지 않으면 나열 순서가 노선을 정하게 된다")
                .isEqualTo(TIED_WEST.stopId());
    }

    @Test
    void 동점_후보의_나열_순서를_뒤집어도_같은_순서가_나온다() {
        StopOrder asListed = engine.order(tieInput(List.of(TIED_WEST, TIED_EAST)));
        StopOrder reversed = engine.order(tieInput(List.of(TIED_EAST, TIED_WEST)));

        assertThat(seqOf(reversed))
                .as("동점이 실제로 존재하는 입력이라야 이 시험이 무는 값이 된다")
                .isEqualTo(seqOf(asListed));
    }

    private static RouteOrderInput tieInput(List<OrderableStop> stops) {
        return new RouteOrderInput(
                TIE_ORIGIN, TIE_DESTINATION, stops, List.of(), Direction.TO_ACADEMY);
    }

    private static RouteOrderInput withStops(RouteOrderInput input, List<OrderableStop> stops) {
        return new RouteOrderInput(
                input.origin(), input.destination(), stops, input.fixedStops(), input.direction());
    }

    private static List<String> seqOf(StopOrder order) {
        return order.sequence().stream()
                .map(stop -> stop.seq() + ":" + stop.stopId() + "/" + stop.waypointId())
                .toList();
    }

    private static GeoPoint point(String lat, String lng) {
        return new GeoPoint(new BigDecimal(lat), new BigDecimal(lng));
    }
}
