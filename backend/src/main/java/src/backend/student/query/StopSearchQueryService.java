package src.backend.student.query;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.student.domain.StopProximity;
import src.backend.student.dto.StopSuggestResponse;
import src.backend.student.entity.Stop;
import src.backend.student.geocoding.spec.GeocodedPoint;
import src.backend.student.geocoding.spec.GeocodingClient;
import src.backend.student.geocoding.spec.GeocodingUnavailableException;
import src.backend.student.geocoding.spec.PlaceSearchClient;
import src.backend.student.repository.StopRepository;

/**
 * 주소 자동완성(고정 노선 편성 화면) — 조회 전용이라 승하차지를 만들지 않는다.
 *
 * <p><b>이 클래스에는 {@code @Transactional} 이 없다</b>(§7 규칙 16) — 지오코딩이 실 API 라 트랜잭션 안에서 부르면
 * 공급자 응답 시간만큼 연결이 열린 채 남는다. 근처 조회는 단일 SELECT 라 트랜잭션 경계가 필요 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StopSearchQueryService {

    /** 자동완성 후보 수 상한 — 장소 5건 + 주소 후보. 목록 한 번에 보이는 만큼. */
    private static final int SUGGEST_LIMIT = 10;

    private final StopRepository stopRepository;

    private final GeocodingClient geocodingClient;

    private final PlaceSearchClient placeSearchClient;

    /**
     * 주소 일부로 후보 여럿을 찾는다(자동완성) — 후보가 없으면 빈 목록이다.
     *
     * <p>0건이 {@code 422} 가 아니다. 입력 중에는 후보가 없는 순간이 흔하고,
     * 그때마다 오류를 띄우면 관계자가 타이핑하는 동안 화면이 경고로 깜빡인다. 공급자 장애는 여전히
     * {@code 503} 이다 — "후보가 없다" 와 "지금 물어볼 수 없다" 는 화면 안내가 다르다.
     */
    public StopSuggestResponse suggest(AuthUser requester, String query) {
        List<GeocodedPoint> candidates;
        try {
            candidates = geocodingClient.candidates(query.trim());
        } catch (GeocodingUnavailableException e) {
            throw new BusinessException(ErrorCode.ADDRESS_VERIFICATION_UNAVAILABLE);
        }
        // 상한을 먼저 자르고 근처 승하차지를 조회한다 — 잘라 낼 후보까지 조회하면 입력마다 질의가 버려진다(BR-127).
        List<PlaceSearchClient.FoundPlace> found = new ArrayList<>(placesOf(query.trim()));
        candidates.forEach(point -> found.add(new PlaceSearchClient.FoundPlace(null, point)));
        return new StopSuggestResponse(found.stream()
                .limit(SUGGEST_LIMIT)
                .map(place -> itemOf(requester, place.name(), place.point()))
                .toList());
    }

    /**
     * 장소 검색(보조 후보)은 실패해도 자동완성을 막지 않는다 — 주소 후보만으로도 쓸 수 있고, 장소 검색은
     * 지도 API 와 다른 키·다른 공급 계약이라 한쪽 장애가 다른 쪽을 끌고 가면 안 된다. 삼키지 않고 남긴다.
     */
    private List<PlaceSearchClient.FoundPlace> placesOf(String query) {
        try {
            return placeSearchClient.search(query);
        } catch (GeocodingUnavailableException e) {
            log.warn("장소 검색 실패 — 주소 후보만 돌려준다: {}", e.getMessage(), e.getCause());
            return List.of();
        }
    }

    private StopSuggestResponse.Item itemOf(AuthUser requester, String placeName, GeocodedPoint point) {
        return new StopSuggestResponse.Item(placeName, point.lat(), point.lng(), point.displayName(),
                nearbyOf(requester.academyId(), point));
    }

    private List<StopSuggestResponse.NearbyStop> nearbyOf(Long academyId, GeocodedPoint point) {
        return stopRepository
                .findNearby(academyId, point.lat(), point.lng(), StopProximity.searchBoxDegrees(point.lat()))
                .stream()
                .map(stop -> new StopSuggestResponse.NearbyStop(stop.getId(), stop.getName(), stop.getAddress(),
                        stop.getLat(), stop.getLng(), distanceOf(stop, point)))
                .filter(nearby -> nearby.distanceM() <= StopProximity.MERGE_RADIUS_METERS)
                .sorted(Comparator.comparingInt(StopSuggestResponse.NearbyStop::distanceM))
                .toList();
    }

    private static int distanceOf(Stop stop, GeocodedPoint point) {
        return (int) Math.round(StopProximity.metersBetween(stop.getLat(), stop.getLng(), point.lat(),
                point.lng()));
    }
}
