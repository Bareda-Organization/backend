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
}
