package src.backend.academy.dto;

/**
 * 관계자 계정 목록 요청(API_SPEC §6.6, Ruling 807) — 컨트롤러가 {@code @RequestParam} 으로 하나씩 받아 이 묶음에 담는다.
 *
 * <p>쿼리 파라미터는 Jackson {@code SNAKE_CASE} 전략을 거치지 않아 {@code @ModelAttribute} 로 묶으면 {@code academy_id} 가 조용히
 * 안 붙는다({@code AuditLogController} 와 같은 근거). 그래서 이름을 컨트롤러가 손으로 적는다.
 *
 * <p>{@code page}·{@code size} 가 {@code Integer} 인 것은 "주지 않음" 과 "0 을 줌" 을 갈라야 하기 때문이다.
 *
 * @param academyId 그 학원 관계자만 — 없으면 전 학원. 미등록 학원이면 {@code 404 ACADEMY_NOT_FOUND}
 * @param q         이름·로그인 아이디 부분 일치(대소문자 무시)
 * @param status    {@code active}(재직) · {@code inactive}(퇴사) — 그 밖의 값은 {@code 422}
 */
public record StaffAccountListRequest(Long academyId, String q, String status, Integer page, Integer size,
        String sort) {
}
