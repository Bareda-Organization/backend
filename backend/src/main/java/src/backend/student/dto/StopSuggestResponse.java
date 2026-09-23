package src.backend.student.dto;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 주소 자동완성 결과(§5.9 고정 노선 편성 화면, 2026-09-23 사용자 지시) — 아무것도 만들지 않은 후보들.
 *
 * <p>후보마다 {@link StopSearchResponse} 와 같은 모양(좌표 · 정규화 주소 · 50m 안 기존 승하차지)이라,
 * 화면은 고른 후보를 다시 묻지 않고 곧장 임시 핀으로 찍는다.
 */
public record StopSuggestResponse(List<Item> items) {

    /**
     * 후보 하나 — {@link StopSearchResponse} 와 같은 필드에 장소 이름을 더한다.
     *
     * @param placeName 장소 검색으로 찾은 후보면 그 이름("현대백화점 목동점"), 주소 후보면 {@code null}(응답에서 빠진다)
     * @param displayName 도로명 주소
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(String placeName, BigDecimal lat, BigDecimal lng, String displayName,
            List<StopSearchResponse.NearbyStop> nearby) {
    }
}
