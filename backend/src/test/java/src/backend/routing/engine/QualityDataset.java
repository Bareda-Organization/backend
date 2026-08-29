package src.backend.routing.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import src.backend.global.common.enums.Direction;
import src.backend.routing.domain.GeoPoint;
import src.backend.routing.engine.spec.FixedStop;
import src.backend.routing.engine.spec.OrderableStop;
import src.backend.routing.engine.spec.OrderedStop;
import src.backend.routing.engine.spec.RouteOrderInput;
import src.backend.routing.engine.spec.StopOrder;

/**
 * 품질 회귀 판정용 고정 좌표 세트를 읽는다 (TECH_DECISIONS §8.5.2).
 *
 * <p>좌표를 테스트 코드가 아니라 <b>리소스 파일</b>에 두는 이유는 데이터셋이 판정의 기준선이기
 * 때문이다. 코드 안에 있으면 리팩터링 중에 슬쩍 바뀌어도 눈에 띄지 않고, 그러면 기록해 둔 상한이
 * 무엇을 재는 값이었는지 알 수 없게 된다.
 *
 * <p>파일이 나열한 {@code stop} 순서가 곧 <b>지그재그(미최적화) 기준선</b>이다 —
 * {@link #zigzagOrder()} 가 그 순서를 그대로 산출 형태로 옮긴다. 고정 자리는 지그재그 쪽에도 똑같이
 * 비워 둔다. 같은 제약 아래에서 비교해야 "엔진이 더 낫다" 가 순서의 이야기가 된다.
 */
record QualityDataset(String name, RouteOrderInput input) {

    /** 이 Phase 가 보유하는 데이터셋 3종 — 도심 밀집 · 교외 분산 · 혼합. */
    static List<String> allNames() {
        return List.of("urban-dense", "suburban-sparse", "mixed");
    }

    static QualityDataset load(String name) {
        Accumulator accumulator = new Accumulator();
        linesOf("/routing/quality/" + name + ".csv").forEach(accumulator::read);
        return new QualityDataset(name, accumulator.toInput());
    }

    /**
     * 입력 순서를 그대로 쓴 미최적화 해 — 하한 판정의 상대다.
     *
     * <p>고정 자리는 엔진과 <b>똑같이</b> 비워 두고 나머지를 파일 나열 순서로 채운다.
     */
    StopOrder zigzagOrder() {
        OrderedStop[] slots = new OrderedStop[input.totalStopCount()];
        for (FixedStop fixed : input.fixedStops()) {
            slots[fixed.seq() - 1] =
                    OrderedStop.ofWaypoint(fixed.waypointId(), fixed.seq(), fixed.point());
        }
        int cursor = 0;
        for (OrderableStop stop : input.stops()) {
            while (slots[cursor] != null) {
                cursor++;
            }
            slots[cursor] = OrderedStop.ofStop(stop.stopId(), cursor + 1, stop.point());
        }
        return new StopOrder(List.of(slots));
    }

    private static List<String> linesOf(String resource) {
        try (InputStream in = QualityDataset.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("데이터셋 리소스가 없다: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code 키=값} 한 줄씩을 받아 입력 한 건으로 쌓는다 — 파일 형식을 아는 유일한 자리다. */
    private static final class Accumulator {

        private final List<OrderableStop> stops = new ArrayList<>();
        private final List<FixedStop> fixedStops = new ArrayList<>();
        private GeoPoint origin;
        private GeoPoint destination;
        private Direction direction;

        private void read(String line) {
            int separator = line.indexOf('=');
            String[] values = line.substring(separator + 1).split(",");
            switch (line.substring(0, separator)) {
                case "direction" -> direction = Direction.valueOf(values[0]);
                case "origin" -> origin = point(values[0], values[1]);
                case "destination" -> destination = point(values[0], values[1]);
                case "stop" -> stops.add(new OrderableStop(
                        Long.parseLong(values[0]), point(values[1], values[2]),
                        Integer.parseInt(values[3])));
                case "fixed" -> fixedStops.add(new FixedStop(
                        Long.parseLong(values[0]), point(values[1], values[2]),
                        Integer.parseInt(values[3])));
                default -> throw new IllegalStateException("모르는 키다: " + line);
            }
        }

        private RouteOrderInput toInput() {
            return new RouteOrderInput(origin, destination, stops, fixedStops, direction);
        }

        private static GeoPoint point(String lat, String lng) {
            return new GeoPoint(new BigDecimal(lat.strip()), new BigDecimal(lng.strip()));
        }
    }
}
