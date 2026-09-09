package src.backend.student.dto;

/**
 * 학생 목록·검색 요청(API_SPEC §5.11 {@code GET /staff/students?q=}) — 쿼리 파라미터를 그대로 담는다.
 *
 * <p>소속 학원을 받는 자리가 없는 것이 사양이다(§1.5) — 어느 학원인지는 토큰이 정하고, 요청이 지정한
 * 값을 신뢰하면 격리를 우회하는 가장 쉬운 경로가 열린다.
 *
 * <p>{@code page}·{@code size} 가 {@code Integer} 인 것은 "주지 않음" 과 "0 을 줌" 을 갈라야 하기
 * 때문이다({@code AcademyListRequest} 와 같은 규약).
 */
public record StudentListRequest(String q, Integer page, Integer size, String sort) {
}
