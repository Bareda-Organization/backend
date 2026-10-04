package src.backend.manager.dto;

import org.springframework.web.bind.annotation.BindParam;

/**
 * 매니저 목록·검색 요청(API_SPEC §5.13 {@code ?q=&role=&linked=} · §1.8).
 *
 * <p>{@code role}·{@code linked} 는 문자열로 받아 {@code ApiValues} 가 옮긴다 — 어긋난 값이 Spring 바인딩 오류({@code 400})가
 * 아니라 {@code 422} 로 나가게 하려는 것이다(Ruling 391). {@code linked} 는 가입 승인이 매니저 후보를 고를 때 쓴다.
 *
 * <p>{@code assigned_today}(오늘 미취소 회차 배치 유무, Ruling 817)는 두 단어라 {@code @ModelAttribute} 가 스네이크케이스 이름을
 * 자바 필드에 못 붙이므로 {@link BindParam} 으로 쿼리 이름을 알려 준다.
 *
 * <p>{@code page}·{@code size} 가 {@code Integer} 인 것은 "주지 않음" 과 "0 을 줌" 을 갈라야 하기
 * 때문이다({@code AcademyListRequest} 와 같은 이유).
 */
public record ManagerListRequest(String q, String role, String linked,
        @BindParam("assigned_today") String assignedToday, Integer page, Integer size, String sort) {
}
