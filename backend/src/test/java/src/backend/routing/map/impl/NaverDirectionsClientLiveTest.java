package src.backend.routing.map.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import src.backend.routing.domain.GeoPoint;
import src.backend.routing.map.spec.CallerPolicy;
import src.backend.routing.map.spec.RoadRoute;
import src.backend.routing.map.spec.RoadRouteRequest;

/**
 * 실 NCP Direction 어댑터 검증 — <b>자격증명이 있을 때만 돈다.</b>
 *
 * <p>테스트 전체 묶음은 결정론적 스텁으로 돌고, 보호 시험은 로컬 HTTP 서버를 공급자 자리에 세운다.
 * 그래서 <b>실 응답의 형태가 우리 파싱과 맞는지</b>는 어디서도 확인되지 않는다 — 필드명이 바뀌거나
 * 호스트가 막혀도 폴백이 값을 채워 초록이 유지된다. 그 한 가지만 여기서 본다.
 *
 * <p>{@link EnabledIf} 는 스프링 컨텍스트가 뜨기 전에 평가되어, 자격증명이 없으면 컨텍스트도 만들지
 * 않는다({@code NaverGeocodingClientLiveTest} 와 같은 방식).
 */
@SpringBootTest(properties = "app.routing.map.provider=naver")
@TestPropertySource(properties = "app.routing.map.provider=naver")
@EnabledIf("자격증명이_있다")
class NaverDirectionsClientLiveTest {

    /** 서울시청. */
    private static final GeoPoint 시청 = new GeoPoint(new BigDecimal("37.566500"), new BigDecimal("126.978000"));

    /** 강남역 — 시청에서 약 10km 남동쪽이다. */
    private static final GeoPoint 강남역 = new GeoPoint(new BigDecimal("37.497900"), new BigDecimal("127.027600"));

    /** 여의도 — 시청·강남역을 잇는 직선에서 크게 서쪽으로 벗어난 경유지(R17 T2 실측용). */
    private static final GeoPoint 여의도 = new GeoPoint(new BigDecimal("37.521900"), new BigDecimal("126.924500"));

    @Autowired
    private NaverDirectionsClient naverDirectionsClient;

    /** yml 과 같은 폴백 순서로 자격증명을 찾는다({@code NAVER_MAPS_*} → {@code NAVER_DIRECTIONS_*}). */
    static boolean 자격증명이_있다() {
        return 값이_있다("NAVER_MAPS_KEY_ID", "NAVER_DIRECTIONS_KEY_ID")
                && 값이_있다("NAVER_MAPS_KEY", "NAVER_DIRECTIONS_KEY");
    }

    private static boolean 값이_있다(String 우선, String 폴백) {
        return 비지_않음(System.getenv(우선)) || 비지_않음(System.getenv(폴백));
    }

    private static boolean 비지_않음(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 실 응답이 우리가 읽는 형태 그대로 온다 — {@code fallbackUsed=false} 가 그 증거다.
     *
     * <p>거리 범위를 함께 보는 이유는 파싱이 어긋나 0이 와도 형태는 맞기 때문이고, 밀리초를 초로
     * 옮기지 않으면 소요 시간만 1000배가 되기 때문이다.
     */
    @Test
    void 실_응답이_폴백_없이_파싱된다() {
        RoadRoute route = naverDirectionsClient.route(
                new RoadRouteRequest(List.of(시청, 강남역), Duration.ofSeconds(10), CallerPolicy.BATCH));

        assertThat(route.fallbackUsed())
                .as("실 응답을 못 읽어 폴백으로 떨어졌다 — 필드명·호스트·자격증명 중 하나가 어긋났다")
                .isFalse();
        assertThat(route.legs()).hasSize(1);
        assertThat(route.legs().getFirst().distanceMeters()).isBetween(5_000, 30_000);
        assertThat(route.legs().getFirst().durationSeconds()).isBetween(300, 5_400);
    }

    /**
     * R15 T1 후속 — 실 NCP 응답의 {@code path} 를 실측으로 확인한다(2026-09-19 curl 실측: 시청→강남역
     * 322점, code=0). 고정 응답(단위·회복 시험)만으로는 <b>실제 API 가 주는 모양 자체가 우리 가정과
     * 맞는지</b>를 검증할 수 없다 — 고정 응답과 파싱이 서로 맞아떨어져도 실물과 다르면 어떤 검사로도
     * 안 드러난다.
     *
     * <p>위경도 범위(33~39 · 124~132, 한반도)로 뒤집힘을 잡는다 — [경도, 위도] 를 [위도, 경도] 로
     * 잘못 읽으면 좌표가 이 범위를 벗어난다({@link GeoPoint} 생성자가 애초에 위도 ±90 밖은 막지만,
     * 그 상한보다 훨씬 좁은 한반도 범위로 더 촘촘히 본다).
     */
    @Test
    void 실_응답의_path가_한반도_범위_안의_좌표로_파싱된다() {
        RoadRoute route = naverDirectionsClient.route(
                new RoadRouteRequest(List.of(시청, 강남역), Duration.ofSeconds(10), CallerPolicy.BATCH));

        assertThat(route.roadPath())
                .as("실 API 가 path 를 안 줬거나 파싱이 비었다")
                .hasSizeGreaterThanOrEqualTo(3);
        assertThat(route.roadPath()).allSatisfy(point -> {
            assertThat(point.lat().doubleValue())
                    .as("위도가 33~39 밖이다 — [경도, 위도] 를 뒤집어 읽었을 가능성")
                    .isBetween(33.0, 39.0);
            assertThat(point.lng().doubleValue())
                    .as("경도가 124~132 밖이다 — [경도, 위도] 를 뒤집어 읽었을 가능성")
                    .isBetween(124.0, 132.0);
        });
        // 첫·끝 좌표가 요청한 출발·도착지 근처인지(스냅 오차 0.01도 이내, 약 1km) — 순서는 맞아도
        // 엉뚱한 배열 원소를 읽으면(예: path 마지막 점을 첫 값으로 오인) 이 대조가 드러낸다.
        assertThat(route.roadPath().getFirst().lat().doubleValue()).isCloseTo(37.5665, org.assertj.core.data.Offset.offset(0.01));
        assertThat(route.roadPath().getFirst().lng().doubleValue()).isCloseTo(126.978, org.assertj.core.data.Offset.offset(0.01));
        assertThat(route.roadPath().getLast().lat().doubleValue()).isCloseTo(37.4979, org.assertj.core.data.Offset.offset(0.01));
        assertThat(route.roadPath().getLast().lng().doubleValue()).isCloseTo(127.0276, org.assertj.core.data.Offset.offset(0.01));
    }

    /**
     * R17 T2 목표 1·2 — 실 응답에 구간별 실측값({@code summary.waypoints[]}·{@code goal})이 실제로
     * 오는지, 그 값이 직선거리 비율 배분과 <b>다른지</b>를 함께 고정한다(2026-09-19 curl 실측 —
     * 시청→여의도→강남역 총 20,278m 중 첫 구간 실측 8,116m, 직선거리 비율 배분은 약 8,503m로
     * 387m 벌어진다. 여의도가 직선 경로에서 크게 서쪽으로 벗어난 경유지라 두 값이 우연히 같아질
     * 여지가 없다).
     *
     * <p>{@code StraightLineLegs.distribute} 를 같은 총합으로 다시 돌려 대조군을 만든다 — 이 값과
     * 실제 응답이 갈리지 않으면, 총합만 실측이고 구간별 배분은 여전히 직선거리 근사라는 뜻이다.
     */
    @Test
    void 실_응답의_구간_배분이_직선거리_비율과_다르다() {
        List<GeoPoint> points = List.of(시청, 여의도, 강남역);

        RoadRoute route = naverDirectionsClient.route(
                new RoadRouteRequest(points, Duration.ofSeconds(10), CallerPolicy.BATCH));

        assertThat(route.fallbackUsed()).isFalse();
        assertThat(route.legs()).hasSize(2);
        int totalMeters = route.legs().stream().mapToInt(src.backend.routing.map.spec.RoadLeg::distanceMeters).sum();
        int totalSeconds = route.legs().stream().mapToInt(src.backend.routing.map.spec.RoadLeg::durationSeconds).sum();

        List<src.backend.routing.map.spec.RoadLeg> 직선비율_배분 =
                StraightLineLegs.distribute(points, totalMeters, totalSeconds);

        assertThat(route.legs().getFirst().distanceMeters())
                .as("첫 구간 실측 거리가 직선거리 비율 배분과 같다 — 총합만 실측이고 구간별 값은 "
                        + "여전히 근사값이다(StraightLineLegs.distribute 가 그대로 쓰이고 있다는 뜻)")
                .isNotEqualTo(직선비율_배분.getFirst().distanceMeters());
    }
}
