package src.backend.student.dto;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 주소 자동완성 결과(§5.9 고정 노선 편성 화면, 2026-09-23 사용자 지시) — 아무것도 만들지 않은 후보들.
 *
 * <p>후보마다 좌표 · 정규화 주소 · 50m 안 기존 승하차지를 싣는 모양이라, 화면은 고른 후보를 다시 묻지 않고 곧장 임시 핀으로 찍는다.
 */
public record StopSuggestResponse(List<Item> items) {

    /**
     * 후보 하나.
     *
     * @param lat 지오코딩이 돌려준 위도. 화면이 지도에 임시 핀으로 찍고, 관계자가 옮길 수 있다
     * @param lng 같은 경도
     * @param nearby 이 좌표 50m 안의 기존 승하차지. 비어 있지 않으면 화면이 "이미 있는 자리" 를 알린다
     * @param placeName 장소 검색으로 찾은 후보면 그 이름("현대백화점 목동점"), 주소 후보면 {@code null}(응답에서 빠진다)
     * @param displayName 도로명 주소
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(String placeName, BigDecimal lat, BigDecimal lng, String displayName,
            List<NearbyStop> nearby) {
    }

    /**
     * 근처 승하차지 한 곳.
     *
     * @param distanceM 검색 좌표에서의 거리(m) — 관계자가 "같은 자리인가" 를 판단하는 값이다
     */
    public record NearbyStop(Long stopId, String name, String address, BigDecimal lat, BigDecimal lng,
            int distanceM) {
    }
}
