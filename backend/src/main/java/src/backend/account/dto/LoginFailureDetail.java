package src.backend.account.dto;

/**
 * 로그인 실패 응답의 부가 정보(API_SPEC §2.5 {@code INVALID_CREDENTIALS.details.remaining_attempts}, C-11).
 * {@link src.backend.global.error.ErrorResponse}의 {@code details}(타입 미고정)에 실린다.
 */
public record LoginFailureDetail(int remainingAttempts) {
}
