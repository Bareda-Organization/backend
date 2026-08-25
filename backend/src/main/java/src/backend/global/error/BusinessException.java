package src.backend.global.error;

/**
 * 비즈니스 규칙 위반을 표현하는 공통 예외.
 * Service 계층에서 던지면 GlobalExceptionHandler 가 ErrorCode 에 맞는 HTTP 응답으로 변환한다.
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Object details;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.details = null;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.details = null;
    }

    /**
     * 코드마다 구조가 다른 부가 정보(§1.10 {@code error.details})를 함께 싣는다 — 이 저장소 최초
     * 소비처는 {@code INVALID_CREDENTIALS.details.remaining_attempts}(API_SPEC §2.5, Phase 2 Task 4)다.
     */
    public BusinessException(ErrorCode errorCode, Object details) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.details = details;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public Object getDetails() {
        return details;
    }
}
