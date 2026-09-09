package src.backend.academy.dto;

/**
 * 학원 목록·검색 요청(API_SPEC §6.1) — 쿼리 파라미터를 그대로 담는다.
 *
 * <p>파라미터를 하나씩 {@code @RequestParam} 으로 받지 않고 묶은 이유는 페이징 3개까지 더하면 핸들러
 * 파라미터가 6개가 되어, 무엇이 검색 조건이고 무엇이 페이징인지 시그니처에서 사라지기 때문이다.
 *
 * <p>{@code page}·{@code size} 가 {@code Integer} 인 것은 "주지 않음" 과 "0 을 줌" 을 갈라야 하기
 * 때문이다 — {@code int} 로 받으면 값을 주지 않은 요청이 0 을 준 요청과 구별되지 않는다.
 */
public record AcademyListRequest(String q, String status, Integer page, Integer size, String sort) {
}
