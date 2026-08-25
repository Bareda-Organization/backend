package src.backend.account.dto;

/**
 * 메인 관리자 콘솔의 계정 목록 요청(API_SPEC §6.6·§6.10) — 페이징 파라미터 3개만 담는다.
 *
 * <p>§6.6·§6.10 어느 쪽도 검색어·필터를 규정하지 않는다. 없는 필터를 미리 만들면 아무도 부르지 않는
 * 조건이 쿼리에 남고, 그 조건은 값이 바뀌어도 아무 단언이 잡지 않는다.
 *
 * <p>{@code page}·{@code size} 가 {@code Integer} 인 것은 "주지 않음" 과 "0 을 줌" 을 갈라야 하기
 * 때문이다({@code AcademyListRequest} 와 같은 근거).
 */
public record AdminAccountListRequest(Integer page, Integer size, String sort) {
}
