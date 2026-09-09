package src.backend.account.dto;

/**
 * 가입 요청 목록 조회의 쿼리 파라미터(API_SPEC §5.1) — 두 승인 축이 같은 형태를 쓴다.
 *
 * <p>{@code status} 를 생략하면 {@code pending} 이다(§5.1) — 승인 큐 화면이 기본 소비처라 처리 완료된
 * 건까지 함께 실으면 미처리 건이 묻힌다. §6.4 는 이 파라미터를 명시하지 않으나 같은 승인 큐라
 * 같은 기본값을 쓴다(판단) — 기본값이 "전체" 이면 처리한 요청이 목록에 영구히 쌓인다.
 *
 * <p>{@code page}·{@code size} 가 {@code Integer} 인 것은 "주지 않음" 과 "0 을 줌" 을 갈라야 하기
 * 때문이다({@code AcademyListRequest} 와 같은 이유).
 */
public record SignupRequestListRequest(String status, Integer page, Integer size, String sort) {
}
