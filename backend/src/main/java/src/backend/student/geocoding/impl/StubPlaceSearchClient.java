package src.backend.student.geocoding.impl;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import src.backend.student.geocoding.spec.GeocodedPoint;
import src.backend.student.geocoding.spec.GeocodingUnavailableException;
import src.backend.student.geocoding.spec.PlaceSearchClient;

/**
 * 테스트용 장소 검색 — {@value #PLACE_MARKER} 가 든 입력에만 장소 하나를 낸다. 그 밖에는 0건이라 장소
 * 검색을 모르는 시험에 끼어들지 않는다. {@value #UNAVAILABLE_MARKER} 는 공급자 장애를 재현한다.
 */
@Component
@ConditionalOnProperty(name = "app.place-search.provider", havingValue = "stub")
public class StubPlaceSearchClient implements PlaceSearchClient {

    public static final String PLACE_MARKER = "장소";

    public static final String UNAVAILABLE_MARKER = "장소장애";

    @Override
    public List<FoundPlace> search(String query) {
        if (query.contains(UNAVAILABLE_MARKER)) {
            throw new GeocodingUnavailableException("스텁 장소 검색 장애 재현: " + query, null);
        }
        if (!query.contains(PLACE_MARKER)) {
            return List.of();
        }
        return List.of(new FoundPlace(query + " 본점",
                new GeocodedPoint(new BigDecimal("37.600000"), new BigDecimal("126.600000"), query + " 1")));
    }
}
