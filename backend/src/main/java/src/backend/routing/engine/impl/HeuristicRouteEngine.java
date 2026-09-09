package src.backend.routing.engine.impl;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import src.backend.routing.engine.spec.RouteEngine;
import src.backend.routing.engine.spec.RouteOrderInput;
import src.backend.routing.engine.spec.StopOrder;

/**
 * 최근접 이웃으로 초기 해를 만들고 2-opt 로 다듬는 결정론적 순서 최적화.
 *
 * <p><b>이 가중치가 옳다고 주장하지 않는다.</b> 거리·시간·정원을 어떻게 섞을지는 아직 미확정이고
 * (PRD §10.1 G · §7.1 P2 후속 F-01), 여기서 고른 값은 그 판단이 내려질 때까지의 잠정값이다. 그래서
 * 값을 코드에 묻어 두지 않고 {@link #policySnapshot()} 으로 함께 내보낸다 — 기준이 바뀐 뒤에도 과거
 * 노선이 어떤 조건에서 나왔는지 재현할 수 있어야 한다(TECH_DECISIONS §8.5.1).
 *
 * <p><b>결정론이 이 구현의 요구 조건이다.</b> 같은 입력이 실행마다 다른 순서를 내면 품질 회귀
 * 판정(TECH_DECISIONS §8.5.2)이 무엇을 재는 값인지 알 수 없어진다. 난수 재시작·시간 제한 같은
 * 흔한 휴리스틱 가속 수단을 쓰지 않은 이유가 이것이다.
 */
@Component
public class HeuristicRouteEngine implements RouteEngine {

    /** {@code route_version.engine_name} 에 실리는 값. 컬럼이 {@code varchar(30)} 이다. */
    private static final String ENGINE_NAME = "heuristic";

    /**
     * 2-opt 를 다시 훑는 최대 횟수.
     *
     * <p>상한을 두는 이유는 수렴 보장이 아니라 <b>최악 실행 시간의 상한</b> 때문이다. 온디맨드
     * 계산은 사용자가 대기 중이라(ARCHITECTURE §8.3) 정차지가 많은 회차에서 개선을 끝까지 좇으면
     * 승인 화면이 그만큼 늦어진다.
     *
     * <p><b>실측 수렴 회차</b> — 품질 데이터셋 3종(10·12·12정차)에서 1~4회, 정차지를 늘린 합성
     * 입력에서 20정차 4회 · 40정차 7회 · 60정차 6회 · 80정차 11회. 버스 정원상 실제 회차가 80정차에
     * 닿을 일이 부재하므로 이 값은 4배 이상 여유가 있다.
     *
     * <p>상한에 걸려 개선이 중간에 끊기면 <b>총 주행거리가 늘어</b> 품질 회귀 판정의 상한 단언이
     * 걸린다(음성 대조 변형 B 로 실증) — 별도 감시 장치를 두지 않는 근거다.
     */
    private static final int MAX_IMPROVEMENT_ROUNDS = 50;

    private static final Map<String, Object> POLICY_SNAPSHOT = policySnapshotOf();

    @Override
    public String name() {
        return ENGINE_NAME;
    }

    @Override
    public Map<String, Object> policySnapshot() {
        return POLICY_SNAPSHOT;
    }

    @Override
    public StopOrder order(RouteOrderInput input) {
        RouteSlots slots = RouteSlots.of(input);
        NearestNeighborSeeder.seed(slots, input);
        TwoOptRefiner.refine(slots, input, MAX_IMPROVEMENT_ROUNDS);
        return slots.toStopOrder();
    }

    private static Map<String, Object> policySnapshotOf() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("objective", "total_distance");
        snapshot.put("distance", "haversine");
        snapshot.put("improvement", "2opt");
        snapshot.put("maxImprovementRounds", MAX_IMPROVEMENT_ROUNDS);
        snapshot.put("tieBreak", "stop_id_asc");
        snapshot.put("fixedStopPlacement", "seq_preserved");
        return Collections.unmodifiableMap(snapshot);
    }
}
