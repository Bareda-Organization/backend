package src.backend.routing.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

import src.backend.routing.engine.impl.HeuristicRouteEngine;
import src.backend.routing.engine.spec.RouteEngine;

/**
 * 산출 조건을 <b>값으로</b> 남기는지 고정한다 (TECH_DECISIONS §8.5.1, 목표 6 중 엔진 몫).
 *
 * <p>{@code engine_name}·{@code policy_snapshot} 두 컬럼은 노선이 배포된 뒤에 "왜 이 순서로
 * 돌았나" 를 되짚는 유일한 근거다. 정책은 바뀌는데 과거 노선은 남으므로, 상수를 가리키기만 하면
 * 지금 코드의 값으로 과거를 설명하게 되어 근거가 아니라 오해가 된다.
 *
 * <p>가중치 기준 자체는 아직 미확정이다(PRD §10.1 G · §7.1 P2 후속 F-01). 그래서 이 시험은 <b>어떤
 * 값이 옳은가</b>를 재지 않고 <b>쓴 값이 남는가</b>만 잰다.
 */
class HeuristicRouteEngineSnapshotTest {

    /** {@code route_version.engine_name} 컬럼 폭. */
    private static final int ENGINE_NAME_COLUMN_LENGTH = 30;

    private final RouteEngine engine = new HeuristicRouteEngine();

    @Test
    void 엔진_이름은_비어있지_않고_컬럼_폭을_넘지_않는다() {
        assertThat(engine.name())
                .isNotBlank()
                .hasSizeLessThanOrEqualTo(ENGINE_NAME_COLUMN_LENGTH);
    }

    @Test
    void 정책_스냅샷에_순서를_정한_값들이_실린다() {
        Map<String, Object> snapshot = engine.policySnapshot();

        assertThat(snapshot)
                .as("빈 맵이면 과거 노선의 산출 조건을 재현할 수단이 부재하다")
                .isNotEmpty()
                .containsKeys("objective", "distance", "improvement", "maxImprovementRounds", "tieBreak");
    }

    @Test
    void 정책_스냅샷은_jsonb_로_직렬화할_수_있는_값만_담는다() {
        assertThat(engine.policySnapshot().values())
                .as("중첩 객체·엔티티가 섞이면 jsonb 컬럼에 그대로 실을 수 없다")
                .allSatisfy(value -> assertThat(value)
                        .isInstanceOfAny(String.class, Number.class, Boolean.class));
    }

    @Test
    void 정책_스냅샷은_호출자가_고칠_수_없다() {
        Map<String, Object> snapshot = engine.policySnapshot();

        assertThatThrownBy(() -> snapshot.put("objective", "조작"))
                .as("호출자가 고칠 수 있으면 저장된 스냅샷이 실제로 쓴 값이라는 보장이 사라진다")
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
