package src.backend.global.error;

import org.springframework.http.HttpStatus;

/**
 * 서비스 전역에서 재사용하는 에러 코드.
 * HTTP 상태와 기본 메시지를 한 곳에 모아 응답 일관성을 유지한다.
 */
public enum ErrorCode {

    INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다"),
    // pending·rejected 계정이 허용 목록 밖 API 를 호출할 때(API_SPEC §1.4·§8.1) — 401 이 아니라 403.
    AUTH_PENDING(HttpStatus.FORBIDDEN, "승인 대기 중인 계정입니다"),
    // blocked 계정의 로그인·API 호출(API_SPEC §1.4·§8.1) — 자격 오류(401)와 구분해야 재시도가 실패 카운터를 올리지 않는다.
    AUTH_ACCOUNT_BLOCKED(HttpStatus.FORBIDDEN, "차단된 계정입니다. 관리자에게 문의하세요"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "대상을 찾을 수 없습니다"),
    CONFLICT(HttpStatus.CONFLICT, "이미 처리된 요청입니다"),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 가입된 이메일입니다"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}
