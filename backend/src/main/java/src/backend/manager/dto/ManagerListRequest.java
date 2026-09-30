package src.backend.manager.dto;

/**
 * 매니저 목록·검색 요청(API_SPEC §5.13 {@code ?q=&role=&linked=} · §1.8).
 *
 * <p>{@code role}·{@code linked} 는 문자열로 받아 {@code ApiValues} 가 옮긴다 — 어긋난 값이 Spring 바인딩 오류({@code 400})가
 * 아니라 {@code 422} 로 나가게 하려는 것이다(Ruling 391). {@code linked} 는 가입 승인이 매니저 후보를 고를 때 쓴다.
 *
 * <p>{@code page}·{@code size} 가 {@code Integer} 인 것은 "주지 않음" 과 "0 을 줌" 을 갈라야 하기
 * 때문이다({@code AcademyListRequest} 와 같은 이유).
 */
public record ManagerListRequest(String q, String role, String linked, Integer page, Integer size, String sort) {
}
