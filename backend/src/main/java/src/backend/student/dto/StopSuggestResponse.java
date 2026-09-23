package src.backend.student.dto;

import java.util.List;

/**
 * 주소 자동완성 결과(§5.9 고정 노선 편성 화면, 2026-09-23 사용자 지시) — 아무것도 만들지 않은 후보들.
 *
 * <p>후보마다 {@link StopSearchResponse} 와 같은 모양(좌표 · 정규화 주소 · 50m 안 기존 승하차지)이라,
 * 화면은 고른 후보를 다시 묻지 않고 곧장 임시 핀으로 찍는다.
 */
public record StopSuggestResponse(List<StopSearchResponse> items) {
}
