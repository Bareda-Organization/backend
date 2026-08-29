package src.backend.routing.dto;

/**
 * 고정 노선 목록 요청(API_SPEC §5.9 · §1.8) — 페이징·정렬 파라미터를 묶는다.
 *
 * <p>{@code page}·{@code size} 가 {@code Integer} 인 것은 "주지 않음" 과 "0 을 줌" 을 갈라야 하기
 * 때문이다({@code ScheduleListRequest} 와 같은 이유).
 */
public record RouteListRequest(Integer page, Integer size, String sort) {
}
