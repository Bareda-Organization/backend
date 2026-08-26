package src.backend.manager.dto;

/**
 * 매니저 목록·검색 요청(API_SPEC §5.13 {@code ?q=} · §1.8).
 *
 * <p>{@code page}·{@code size} 가 {@code Integer} 인 것은 "주지 않음" 과 "0 을 줌" 을 갈라야 하기
 * 때문이다({@code AcademyListRequest} 와 같은 이유).
 */
public record ManagerListRequest(String q, Integer page, Integer size, String sort) {
}
