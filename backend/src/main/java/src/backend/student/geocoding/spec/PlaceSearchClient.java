package src.backend.student.geocoding.spec;

import java.util.List;

/**
 * 장소 이름으로 찾는다(주소 자동완성의 보조 후보, 2026-09-23) — "목동 현대백화점" 처럼 주소가 아닌 입력을
 * 지오코딩은 못 찾는다.
 *
 * @throws GeocodingUnavailableException 공급자에 닿지 못했을 때
 */
public interface PlaceSearchClient {

    List<FoundPlace> search(String query);

    /** 찾은 장소 하나 — 이름은 표시명 기본값, 점의 표시명은 도로명 주소다. */
    record FoundPlace(String name, GeocodedPoint point) {
    }
}
