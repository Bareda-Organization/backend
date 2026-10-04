package src.backend.student.dto;

import org.springframework.web.bind.annotation.BindParam;

/**
 * 학생 목록·검색 요청(API_SPEC §5.11 {@code GET /staff/students?q=}) — 쿼리 파라미터를 그대로 담는다.
 *
 * <p>소속 학원을 받는 자리가 없는 것이 사양이다(§1.5) — 어느 학원인지는 토큰이 정하고, 요청이 지정한
 * 값을 신뢰하면 격리를 우회하는 가장 쉬운 경로가 열린다.
 *
 * <p>{@code page}·{@code size} 가 {@code Integer} 인 것은 "주지 않음" 과 "0 을 줌" 을 갈라야 하기
 * 때문이다({@code AcademyListRequest} 와 같은 규약).
 *
 * <p>{@code class_name}(반 이름 일치)·{@code filter}({@code guardian_unlinked}·{@code address_missing} — 그 밖의 값은
 * {@code 422})는 R48 이 더했다(Ruling 815). {@code class_name} 은 두 단어라 {@code @ModelAttribute} 가 스네이크케이스
 * 이름을 자바 필드에 못 붙이므로 {@link BindParam} 으로 쿼리 이름을 직접 알려 준다.
 */
public record StudentListRequest(String q, @BindParam("class_name") String className, String filter, Integer page,
        Integer size, String sort) {
}
