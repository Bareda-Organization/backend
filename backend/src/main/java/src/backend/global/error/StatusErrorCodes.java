package src.backend.global.error;

/** 프레임워크 예외가 싣고 온 HTTP 상태를 이 서비스의 에러 코드로 옮긴다 — 사양의 에러 사전(§8)에 없는 상태를 새 코드 없이 같은 어휘로 답하게 한다. */
public final class StatusErrorCodes {

    private StatusErrorCodes() {
    }

    /** 401 · 403 · 404 · 405 는 같은 상태의 코드로, 5xx 는 {@code INTERNAL_ERROR}, 그 밖의 4xx(400 · 406 · 415 등)는 형식 위반 {@code VALIDATION_FAILED} 로 옮긴다. */
    public static ErrorCode of(int status) {
        return switch (status) {
            case 401 -> ErrorCode.UNAUTHORIZED;
            case 403 -> ErrorCode.FORBIDDEN;
            case 404 -> ErrorCode.ENDPOINT_NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            default -> status >= 500 ? ErrorCode.INTERNAL_ERROR : ErrorCode.VALIDATION_FAILED;
        };
    }
}
