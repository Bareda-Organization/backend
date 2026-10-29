package src.backend.student.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.AuthUser;
import src.backend.student.geocoding.spec.GeocodedPoint;
import src.backend.student.geocoding.spec.GeocodingClient;
import src.backend.student.geocoding.spec.PlaceSearchClient;
import src.backend.student.repository.StopRepository;

/**
 * 주소 자동완성은 돌려줄 후보(상한 10)에 대해서만, 한 번의 질의로 근처 승하차지를 조회한다(BR-127) — 입력마다 불리는
 * 경로라, 잘라 낼 후보까지 조회하거나 후보마다 따로 물으면 버려질 질의가 입력 횟수만큼 반복된다.
 *
 * <p>시험용 가짜 공급자는 후보를 최대 4건만 줘 상한을 넘지 않으므로 공급자를 직접 흉내 낸다.
 */
class StopSearchQueryServiceTest {

    @Test
    void 상한을_넘는_후보는_근처_승하차지를_조회하지_않는다() {
        GeocodingClient geocodingClient = mock(GeocodingClient.class);
        PlaceSearchClient placeSearchClient = mock(PlaceSearchClient.class);
        StopRepository stopRepository = mock(StopRepository.class);
        List<PlaceSearchClient.FoundPlace> places = IntStream.range(0, 5)
                .mapToObj(i -> new PlaceSearchClient.FoundPlace("장소" + i, point(i))).toList();
        List<GeocodedPoint> candidates = IntStream.range(0, 10).mapToObj(i -> point(10 + i)).toList();
        given(placeSearchClient.search("테헤란")).willReturn(places);
        given(geocodingClient.candidates("테헤란")).willReturn(candidates);
        StopSearchQueryService service = new StopSearchQueryService(stopRepository, geocodingClient,
                placeSearchClient);

        assertThat(service.suggest(new AuthUser(2L, 1L, Role.STAFF, AccountStatus.ACTIVE), "테헤란").items())
                .hasSize(10);
        ArgumentCaptor<BigDecimal> minLat = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> maxLat = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> minLng = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> maxLng = ArgumentCaptor.forClass(BigDecimal.class);
        verify(stopRepository, times(1)).findNearby(anyLong(), minLat.capture(), maxLat.capture(), minLng.capture(),
                maxLng.capture());
        List<GeocodedPoint> shown = Stream.concat(places.stream().map(PlaceSearchClient.FoundPlace::point),
                candidates.stream().limit(5)).toList();
        List<GeocodedPoint> cutOff = candidates.subList(5, candidates.size());
        assertThat(shown).as("보여 줄 후보는 모두 질의 범위 안이다").allSatisfy(point -> assertThat(inRange(point, minLat, maxLat,
                minLng, maxLng)).isTrue());
        assertThat(cutOff).as("상한에서 잘린 후보는 질의 범위 밖이다").allSatisfy(point -> assertThat(inRange(point, minLat,
                maxLat, minLng, maxLng)).isFalse());
    }

    private static boolean inRange(GeocodedPoint point, ArgumentCaptor<BigDecimal> minLat,
            ArgumentCaptor<BigDecimal> maxLat, ArgumentCaptor<BigDecimal> minLng, ArgumentCaptor<BigDecimal> maxLng) {
        return point.lat().compareTo(minLat.getValue()) >= 0 && point.lat().compareTo(maxLat.getValue()) <= 0
                && point.lng().compareTo(minLng.getValue()) >= 0 && point.lng().compareTo(maxLng.getValue()) <= 0;
    }

    private static GeocodedPoint point(int i) {
        return new GeocodedPoint(new BigDecimal("37.500000").add(BigDecimal.valueOf(i, 3)),
                new BigDecimal("127.000000"), "주소 " + i);
    }
}
