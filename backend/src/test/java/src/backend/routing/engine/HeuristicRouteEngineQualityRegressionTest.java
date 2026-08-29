package src.backend.routing.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import src.backend.routing.engine.impl.HeuristicRouteEngine;
import src.backend.routing.engine.spec.RouteEngine;

/**
 * 순서 최적화의 <b>품질이 나빠지면 실패</b>하는 회귀 판정 (TECH_DECISIONS §8.5.2).
 *
 * <p>다른 테스트들이 "규칙을 지키는가" 를 보는 것과 달리 이쪽은 "전보다 나빠졌는가" 를 본다.
 * 알고리즘 교체가 상시화될 자리라(PRD §10.1 G 가 열려 있다) 교체 전후의 우열을 숫자로 남길 수단이
 * 없으면, 바꾼 뒤 나빠졌는지 알 방법이 부재하다.
 *
 * <h2>상한과 하한을 둘 다 두는 이유</h2>
 *
 * <p><b>하한</b>("지그재그보다 나쁘지 않다")만 두면 거의 아무것도 검사하지 않는다 — 최적화를
 * 통째로 지워 입력 순서를 그대로 반환해도 등호로 통과한다. <b>상한</b>(아래 기록된 실측값)만 두면
 * 그 값이 얼마나 헐거운지 알 수 없다. 그래서 셋째로
 * {@code 기록된_상한이_지그재그_기준선보다_엄격하다} 를 둬서 상한이 실제로 무는 값인지 확인한다.
 *
 * <h2>여유 폭 {@value #REGRESSION_MARGIN} 을 고른 근거</h2>
 *
 * <p>엔진이 결정론적이라 원칙적으로는 여유 0 이 가능하다. 그럼에도 폭을 둔 것은 Haversine 이
 * {@code Math.sin}·{@code Math.asin} 을 쓰고 JDK 가 이들에 1~2 ulp 오차를 허용해, 플랫폼이 바뀌면
 * 마지막 자리가 갈릴 수 있기 때문이다. 그 오차는 구간 열댓 개를 더해도 상대 1e-14 규모라 2% 는
 * 압도적으로 넉넉하다. 반대쪽에서 이 폭이 결함을 가리지 않는 근거는 <b>실측</b>이다 — 기록 시점의
 * 엔진 산출은 지그재그 기준선의 총 주행거리 40~64% · 최대 탑승시간 44~69% 였다. 여유 2% 는 그
 * 간격의 20분의 1도 되지 않아, 순서가 한 자리만 나빠져도 상한에 걸린다.
 */
class HeuristicRouteEngineQualityRegressionTest {

    /** 기록된 실측값 대비 허용 여유. 근거는 클래스 주석. */
    private static final double REGRESSION_MARGIN = 0.02;

    private final RouteEngine engine = new HeuristicRouteEngine();

    /**
     * 현재 구현의 <b>실측값</b> — 기준선이지 목표값이 아니다.
     *
     * <p>알고리즘을 바꿔 이 값이 갱신될 때는 <b>내려가는 방향</b>이어야 한다. 올려 적어 통과시키는
     * 것은 판정을 버리는 것과 같으므로, 올려야 한다면 그것이 곧 회귀 보고다.
     */
    private static Map<String, Baseline> baselines() {
        return Map.of(
                "urban-dense", new Baseline(3914.80d, 16.63d, 12),
                "suburban-sparse", new Baseline(62615.85d, 145.54d, 10),
                "mixed", new Baseline(61386.61d, 148.19d, 12));
    }

    private record Baseline(double distanceMeters, double maxRideMinutes, int stopCount) {}

    static List<String> datasets() {
        return QualityDataset.allNames();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("datasets")
    void 총_주행거리가_기록된_실측값을_넘지_않는다(String name) {
        QualityDataset dataset = QualityDataset.load(name);
        RouteQualityMetrics measured =
                RouteQualityMetrics.of(dataset.input(), engine.order(dataset.input()));

        assertThat(measured.totalDistanceMeters())
                .as("%s — 총 주행거리가 기록된 실측값보다 늘었다면 순서가 나빠진 것이다", name)
                .isLessThanOrEqualTo(upperBound(baselines().get(name).distanceMeters()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("datasets")
    void 최대_학생_탑승시간이_기록된_실측값을_넘지_않는다(String name) {
        QualityDataset dataset = QualityDataset.load(name);
        RouteQualityMetrics measured =
                RouteQualityMetrics.of(dataset.input(), engine.order(dataset.input()));

        assertThat(measured.maxRideMinutes())
                .as("%s — 총 주행거리가 줄어도 한 아이가 더 오래 앉아 있으면 나빠진 것이다", name)
                .isLessThanOrEqualTo(upperBound(baselines().get(name).maxRideMinutes()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("datasets")
    void 정차_수가_입력_정차지_수와_같다(String name) {
        QualityDataset dataset = QualityDataset.load(name);
        RouteQualityMetrics measured =
                RouteQualityMetrics.of(dataset.input(), engine.order(dataset.input()));

        assertThat(measured.stopCount())
                .as("%s — 정차지를 빠뜨리거나 겹쳐 넣으면 거리가 줄어도 통과해서는 안 된다", name)
                .isEqualTo(baselines().get(name).stopCount())
                .isEqualTo(dataset.input().totalStopCount());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("datasets")
    void 엔진_산출이_지그재그보다_세_지표_모두에서_나쁘지_않다(String name) {
        QualityDataset dataset = QualityDataset.load(name);
        RouteQualityMetrics engineMetrics =
                RouteQualityMetrics.of(dataset.input(), engine.order(dataset.input()));
        RouteQualityMetrics zigzag =
                RouteQualityMetrics.of(dataset.input(), dataset.zigzagOrder());

        assertThat(engineMetrics.totalDistanceMeters())
                .as("%s — 총 주행거리", name)
                .isLessThanOrEqualTo(zigzag.totalDistanceMeters());
        assertThat(engineMetrics.maxRideMinutes())
                .as("%s — 최대 학생 탑승시간", name)
                .isLessThanOrEqualTo(zigzag.maxRideMinutes());
        assertThat(engineMetrics.stopCount())
                .as("%s — 정차 수", name)
                .isEqualTo(zigzag.stopCount());
    }

    /**
     * 기록된 상한이 <b>무는 값인지</b> 확인한다.
     *
     * <p>이 단언이 없으면 상한을 아무리 헐겁게 적어도 위 두 시험이 초록이라, 회귀 판정이 있다는
     * 사실만 남고 판정력은 사라진다.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("datasets")
    void 기록된_상한이_지그재그_기준선보다_엄격하다(String name) {
        QualityDataset dataset = QualityDataset.load(name);
        RouteQualityMetrics zigzag =
                RouteQualityMetrics.of(dataset.input(), dataset.zigzagOrder());
        Baseline baseline = baselines().get(name);

        assertThat(upperBound(baseline.distanceMeters()))
                .as("%s — 상한이 지그재그보다 헐거우면 상한이 아무것도 막지 않는다", name)
                .isLessThan(zigzag.totalDistanceMeters());
        assertThat(upperBound(baseline.maxRideMinutes()))
                .as("%s — 탑승시간 상한도 지그재그보다 엄격해야 한다", name)
                .isLessThan(zigzag.maxRideMinutes());
    }

    /**
     * 엔진이 <b>협력 객체를 받지 않는다</b> — 품질 판정이 외부 호출을 탈 수 없다는 뜻이다.
     *
     * <p>TECH_DECISIONS §8.5.2 가 "지도 API 미호출" 을 요구하는 이유는 요금·레이트리밋·응답 편차가
     * 판정에 섞이면 그 초록이 코드에 대해 아무것도 말하지 않기 때문이다. 호출 여부를 사후에 세는
     * 대신 <b>부를 대상을 쥘 수 없게</b> 고정한다.
     */
    @Test
    void 엔진은_협력_객체를_주입받지_않는다() {
        assertThat(HeuristicRouteEngine.class.getDeclaredConstructors())
                .allSatisfy(constructor -> assertThat(constructor.getParameterCount())
                        .as("생성자 인자가 생기면 그 자리로 외부 호출이 들어올 수 있다")
                        .isZero());
    }

    private static double upperBound(double baseline) {
        return baseline * (1 + REGRESSION_MARGIN);
    }
}
