package src.backend.global.error;

/**
 * 비즈니스 규칙 위반을 표현하는 공통 예외.
 * Service 계층에서 던지면 GlobalExceptionHandler 가 ErrorCode 에 맞는 HTTP 응답으로 변환한다.
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
