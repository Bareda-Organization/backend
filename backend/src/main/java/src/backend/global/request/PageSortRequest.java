package src.backend.global.request;

/**
 * 페이징·정렬만 받는 목록 요청(API_SPEC §1.8) — 차량 · 고정 노선 · 스케줄 · 메인 관리자 계정 목록이 함께 쓴다.
 *
 * <p>{@code page}·{@code size} 가 {@code Integer} 인 것은 "주지 않음" 과 "0 을 줌" 을 갈라야 하기 때문이다. 검색어 같은 필터가 더 있는
 * 목록은 필터 필드를 더한 자기 요청 record 를 둔다.
 */
public record PageSortRequest(Integer page, Integer size, String sort) {
}
