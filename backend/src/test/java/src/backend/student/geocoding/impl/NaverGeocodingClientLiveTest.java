package src.backend.student.geocoding.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import src.backend.student.geocoding.spec.GeocodedPoint;

/**
 * 실 네이버 Geocoding 어댑터 검증(C-18 · Ruling 157) — <b>자격증명이 있을 때만 돈다.</b>
 *
 * <p>테스트 전체 묶음은 결정론적 스텁으로 돈다({@code build.gradle} 의 {@code geocoding.provider=stub}).
 * 그래서 실 응답의 형태가 우리 파싱과 맞는지는 어디서도 확인되지 않는다 — 필드명이 바뀌거나 호스트가
 * 막혀도 초록이 유지된다. 그 한 가지만 여기서 본다.
 *
 * <p><b>자격증명이 없으면 건너뛴다.</b> 네트워크·요금·NCP 계정 상태가 판정을 바꾸면 그 초록은 코드에
 * 대해 아무것도 말하지 않으므로, 이 클래스는 기본 경로가 아니라 조건부다. {@link EnabledIf} 는 스프링
 * 컨텍스트가 뜨기 전에 평가되어, 자격증명이 없으면 컨텍스트도 만들지 않는다.
 *
 * <p>{@code @TestPropertySource} 로 {@code naver} 를 되돌린다 — 인라인 속성이 시스템 속성보다 앞서므로
 * {@code build.gradle} 의 {@code stub} 을 덮는다. 어댑터를 손으로 {@code new} 하지 않는 이유는
 * <b>배선까지</b> 보기 위해서다: 공급자 선택 · {@code base-url} · 환경변수 폴백이 전부 맞아야 이
 * 테스트가 통과한다.
 */
@SpringBootTest
@TestPropertySource(properties = "geocoding.provider=naver")
@EnabledIf("자격증명이_있다")
class NaverGeocodingClientLiveTest {

    /** 좌표가 알려진 주소 — 서울시청이고 위도 37.5 · 경도 127.0 부근이다. */
    private static final String SEOUL_CITY_HALL = "서울특별시 중구 세종대로 110";

    @Autowired
    private NaverGeocodingClient naverGeocodingClient;

    /**
     * yml 과 <b>같은 폴백 순서</b>로 자격증명을 찾는다({@code NAVER_MAPS_*} → {@code NAVER_DIRECTIONS_*}).
     *
     * <p>순서를 여기서 다시 적는 것이 중복이나, 갈라 두면 yml 은 키를 찾는데 이 테스트만 건너뛰는
     * 상태가 조용히 생긴다 — 그때 실 어댑터는 아무에게도 검증되지 않는다.
     */
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
     * 실 응답이 우리가 읽는 형태 그대로 온다 — 좌표가 실제 위치에 떨어지는지까지 본다.
     *
     * <p>{@code isPresent()} 만 보면 부족하다. {@code x}·{@code y} 는 문자열이고 <b>경도가 앞</b>이라,
     * 둘을 바꿔 읽어도 값은 채워지고 오직 위치만 엉뚱한 곳으로 간다. 서울 좌표 범위를 함께 보는 이유가
     * 그것이다.
     */
    @Test
    void 실_응답의_좌표가_서울_도심에_떨어진다() {
        Optional<GeocodedPoint> found = naverGeocodingClient.geocode(SEOUL_CITY_HALL);

        assertThat(found).isPresent();
        GeocodedPoint point = found.orElseThrow();
        assertThat(point.lat()).isBetween(new BigDecimal("37.0"), new BigDecimal("38.0"));
        assertThat(point.lng()).isBetween(new BigDecimal("126.0"), new BigDecimal("128.0"));
        assertThat(point.displayName()).isNotBlank();
    }

    /** 없는 주소는 예외가 아니라 결과 0건이다 — {@code 422}(저장 보류)로 옮겨질 자리다. */
    @Test
    void 없는_주소는_예외가_아니라_결과_0건이다() {
        assertThat(naverGeocodingClient.geocode("존재하지않는도로명 999999")).isEmpty();
    }
}
