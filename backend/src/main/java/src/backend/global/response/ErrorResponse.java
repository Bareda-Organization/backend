package src.backend.global.response;

/**
 * 실패 REST 응답 봉투(API_SPEC §1.10) — {@code error.code}·{@code error.message}·{@code error.details}.
 * {@code code}는 에러 코드 사전(§8)의 값 그대로라 클라이언트가 문구가 아니라 이 값으로 분기한다
 * (예: {@code AUTH_PENDING} 은 승인 대기 화면, {@code AUTH_REJECTED} 는 거절 사유 화면).
 */
public record ErrorResponse(Error error) {

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(new Error(code, message, null));
    }

    public static ErrorResponse of(String code, String message, Object details) {
        return new ErrorResponse(new Error(code, message, details));
    }

    /** {@code details} 는 코드마다 구조가 다른 부가 정보라 고정 타입을 두지 않는다(§1.10). */
    public record Error(String code, String message, Object details) {
    }
}
