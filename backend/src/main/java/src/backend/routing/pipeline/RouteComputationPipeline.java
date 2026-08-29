package src.backend.routing.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import src.backend.routing.domain.GeoPoint;
import src.backend.routing.engine.spec.OrderedStop;
import src.backend.routing.engine.spec.RouteEngine;
import src.backend.routing.engine.spec.RouteOrderInput;
import src.backend.routing.engine.spec.StopOrder;
import src.backend.routing.map.spec.MapRouteClient;
import src.backend.routing.map.spec.RoadRoute;
import src.backend.routing.map.spec.RoadRouteRequest;

/**
 * 노선 계산 파이프라인 — {@code ARCHITECTURE §8.2} 의 ①좌표 해석 → ②순서 최적화 → ③도로 경로 →
 * ④ETA 산출을 한 흐름으로 잇는다.
 *
 * <p><b>②③은 부르기만 한다.</b> 순서를 어떻게 정하는지는 {@link RouteEngine}, 도로 경로를 어떻게
 * 얻고 실패를 어떻게 삼키는지는 {@link MapRouteClient} 가 안다 — 최적화 기준이 미확정이고(PRD
 * §10.1 G) 지도 공급자가 바뀔 수 있어, 그 둘을 여기 박아 넣으면 하나가 바뀔 때 나머지 단계까지
 * 함께 흔들린다.
 *
 * <p><b>⑤동승자 자동 배정은 이 클래스가 부르지 않는다.</b> 그 단계는 여기서 나온
 * {@code estDurationMin} 을 입력으로 받아야 성립하므로(ARCHITECTURE §8.2 ⑤), 계산이 끝난 뒤 호출자가
 * 잇는다.
 *
 * <p><b>트랜잭션을 걸지 않는다</b> — 외부 지도 API 호출이 그 안에 들어가면 공급자가 느린 만큼 DB
 * 커넥션을 붙든 채 대기한다. DB 를 읽는 것은 ①단계뿐이고 그 안에서만 읽기 트랜잭션이 열린다.
 */
@Component
public class RouteComputationPipeline {

    private final DailyStopResolver stopResolver;

    private final RouteEngine routeEngine;

    private final MapRouteClient mapRouteClient;

    public RouteComputationPipeline(DailyStopResolver stopResolver, RouteEngine routeEngine,
            MapRouteClient mapRouteClient) {
        this.stopResolver = stopResolver;
        this.routeEngine = routeEngine;
        this.mapRouteClient = mapRouteClient;
    }

    /**
     * 회차 1건의 노선을 계산한다 — 좌표를 얻지 못한 학생이 있어도 <b>나머지로 계산을 마친다.</b>
     *
     * <p>결과를 저장하지 않는다. 확정 노선 행을 만드는 것은 확정 배치(Phase 7)의 일이다.
     */
    public RouteComputation compute(RouteComputationInput input) {
        DailyStopResolution resolution = stopResolver.resolve(input.roster());
        StopOrder order = routeEngine.order(orderInputOf(input, resolution));
        RoadRoute road = mapRouteClient.route(roadRequestOf(input, order));
        RouteEtaSchedule schedule = RouteEtaSchedule.accumulate(road, input.departAt(), order.stopCount());
        return new RouteComputation(order.sequence(), schedule.estDurationMin(), schedule.estDistanceKm(),
                schedule.etas(), resolution.unresolvedStudentIds(), snapshotOf(input, road.fallbackUsed()));
    }

    private static RouteOrderInput orderInputOf(RouteComputationInput input, DailyStopResolution resolution) {
        return new RouteOrderInput(input.origin(), input.destination(), resolution.stops(),
                input.fixedStops(), input.roster().direction());
    }

    /**
     * 지점열은 <b>출발지 → 정차지들 → 도착지</b> 순이다 — 순서를 정하는 것은 ②단계이고 여기서 다시
     * 손대면 최적화가 정한 순서와 실제로 조회한 경로가 갈린다.
     */
    private static RoadRouteRequest roadRequestOf(RouteComputationInput input, StopOrder order) {
        List<GeoPoint> points = new ArrayList<>(order.stopCount() + 2);
        points.add(input.origin());
        for (OrderedStop stop : order.sequence()) {
            points.add(stop.point());
        }
        points.add(input.destination());
        return new RoadRouteRequest(points, input.policy().mapTimeout(), input.policy().caller());
    }

    /**
     * 엔진의 정책값에 파이프라인이 쓴 값을 <b>겹치지 않게</b> 더한다.
     *
     * <p>키가 겹치면 던지는 이유는 덮어쓰기가 조용하기 때문이다 — 엔진이 나중에 같은 이름의 값을
     * 내보내기 시작하면 스냅샷에는 파이프라인 값만 남고, 그 기록은 "이 조건에서 나왔다" 고 말하면서
     * 실제로는 다른 조건을 가리키는 <b>틀린 근거</b>가 된다.
     */
    private ComputationSnapshot snapshotOf(RouteComputationInput input, boolean fallbackUsed) {
        Map<String, Object> policy = new LinkedHashMap<>(routeEngine.policySnapshot());
        put(policy, "mapTimeoutMs", input.policy().mapTimeout().toMillis());
        put(policy, "caller", input.policy().caller().name());
        put(policy, "dwellSecondsPerStop", RouteEtaSchedule.DWELL_SECONDS_PER_STOP);
        return new ComputationSnapshot(routeEngine.name(), policy, input.policy().trigger(), fallbackUsed);
    }

    private static void put(Map<String, Object> snapshot, String key, Object value) {
        if (snapshot.put(key, value) != null) {
            throw new IllegalStateException("엔진 정책값과 파이프라인 정책값의 키가 겹친다: " + key);
        }
    }
}
