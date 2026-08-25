package src.backend.global.response;

/**
 * 성공 REST 응답을 감싸는 공통 포맷. 실패 응답은 이 타입을 재사용하지 않고
 * {@link ErrorResponse}(API_SPEC §1.10 {@code error.code}·{@code error.message}·{@code error.details})를
 * 쓴다 — 하나의 레코드로 성공·실패를 겸하면 실패에 코드를 실을 자리가 없어, 클라이언트가
 * {@code AUTH_PENDING} 과 {@code FORBIDDEN} 처럼 같은 403 을 문자열 메시지로만 구별해야 하는
 * 문제가 생긴다(Phase 2 Task 2 리뷰 라운드 1 Important #6).
 */
public record ApiResponse<T>(boolean success, T data, String message) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }
}
