package src.backend.routing.map.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import src.backend.routing.domain.GeoPoint;
import src.backend.routing.map.spec.CallerPolicy;
import src.backend.routing.map.spec.RoadRoute;
import src.backend.routing.map.spec.RoadRouteRequest;

/**
 * 스텁이 <b>결정론적</b>이고 <b>폴백과 수치가 갈리는지</b>를 고정한다.
 *
 * <p>테스트 전체 묶음이 이 구현으로 돌기 때문에(build.gradle 의 {@code app.routing.map.provider=stub})
 * 여기가 흔들리면 상위 단계의 거리·시각 단언이 실행마다 갈린다. 폴백과 값이 같아지면
 * {@code fallbackUsed} 만 다르고 거리·시간이 같아, 근사 경로를 실제 경로로 저장하는 구현이
 * 어디에서도 드러나지 않는다.
 */
class StubMapRouteClientTest {

    private final StubMapRouteClient stub = new StubMapRouteClient();

    @Test
    void 같은_지점열은_언제나_같은_값을_낸다() {
        RoadRouteRequest request = 요청(지점_여러개(4));

        assertThat(stub.route(request)).isEqualTo(stub.route(request));
    }

    @Test
    void 구간_수가_지점_수보다_하나_적다() {
        List<GeoPoint> points = 지점_여러개(5);

        RoadRoute route = stub.route(요청(points));

        assertThat(route.fallbackUsed()).isFalse();
        assertThat(route.legs()).hasSize(points.size() - 1);
    }

    /**
     * R15 T1 목표 7 — local·demo 화면이 빈 경로를 받지 않도록 스텁도 좌표를 준다.
     */
    @Test
    void 정상_응답도_road_path_를_준다() {
        List<GeoPoint> points = 지점_여러개(4);

        RoadRoute route = stub.route(요청(points));

        assertThat(route.roadPath()).isNotEmpty();
        assertThat(route.roadPath().getFirst()).isEqualTo(points.getFirst());
        assertThat(route.roadPath().getLast()).isEqualTo(points.getLast());
    }

    /**
     * 마커 위도가 든 지점열은 폴백 경로를 낸다 — 상위 단계가
     * {@code ComputationSnapshot.fallbackUsed} 를 검사할 수 있는 유일한 입력이다.
     */
    @Test
    void 마커_위도가_있으면_폴백_경로를_낸다() {
        List<GeoPoint> points = List.of(
                new GeoPoint(new BigDecimal("37.500000"), new BigDecimal("127.000000")),
                new GeoPoint(StubMapRouteClient.UNAVAILABLE_MARKER_LAT, new BigDecimal("127.000000")));

        RoadRoute route = stub.route(요청(points));

        assertThat(route.fallbackUsed()).isTrue();
        assertThat(route.legs()).hasSize(1);
        // R15 T1 목표 6 — 근사 경로(StraightLineLegs.approximate)도 좌표를 낸다. StubMapRouteClient
        // 를 거치지 않는 §5.19 응답 시험(StaffRunRouteControllerTest)은 fixture 가 좌표를 직접
        // 주입해 이 경로를 타지 않으므로, approximate() 자체의 좌표 산출은 여기서만 검증된다.
        assertThat(route.roadPath())
                .as("근사 경로에 좌표가 없으면 화면이 fallback_used=true 여도 그릴 선이 부재하다")
                .isNotEmpty();
    }

    /** 정상 값과 폴백 값이 같으면 둘을 가르는 단언이 아무것도 검사하지 않는다. */
    @Test
    void 정상_값은_폴백_값과_다르다() {
        List<GeoPoint> points = 지점_여러개(3);

        RoadRoute 정상 = stub.route(요청(points));
        RoadRoute 폴백 = StraightLineLegs.approximate(points);

        assertThat(정상.legs().getFirst().distanceMeters())
                .isNotEqualTo(폴백.legs().getFirst().distanceMeters());
        assertThat(정상.legs().getFirst().durationSeconds())
                .isNotEqualTo(폴백.legs().getFirst().durationSeconds());
    }

    private static RoadRouteRequest 요청(List<GeoPoint> points) {
        return new RoadRouteRequest(points, Duration.ofSeconds(1), CallerPolicy.BATCH);
    }

    private static List<GeoPoint> 지점_여러개(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> new GeoPoint(
                        new BigDecimal("37.500000").add(new BigDecimal("0.010000").multiply(BigDecimal.valueOf(i))),
                        new BigDecimal("127.000000")))
                .map(GeoPoint.class::cast)
                .toList();
    }
}
