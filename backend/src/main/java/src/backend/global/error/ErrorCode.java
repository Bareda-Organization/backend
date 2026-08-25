package src.backend.global.error;

import org.springframework.http.HttpStatus;

/**
 * 서비스 전역에서 재사용하는 에러 코드.
 * HTTP 상태와 기본 메시지를 한 곳에 모아 응답 일관성을 유지한다.
 */
public enum ErrorCode {

    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다"),
    // pending 계정이 허용 목록 밖 API 를 호출할 때(API_SPEC §1.4·§8.1) — 401 이 아니라 403.
    AUTH_PENDING(HttpStatus.FORBIDDEN, "승인 대기 중인 계정입니다"),
    // rejected 계정이 허용 목록 밖 API 를 호출할 때(API_SPEC §8.1, 2026-08-25 신설) — AUTH_PENDING 과
    // 코드를 나눠, "승인 대기 중"과 "거절됨"을 클라이언트가 구별해 대기 화면에 거절 사유를 보여줄 수 있게 한다.
    AUTH_REJECTED(HttpStatus.FORBIDDEN, "가입이 거절된 계정입니다"),
    // blocked 계정의 로그인·API 호출(API_SPEC §1.4·§8.1) — 자격 오류(401)와 구분해야 재시도가 실패 카운터를 올리지 않는다.
    AUTH_ACCOUNT_BLOCKED(HttpStatus.FORBIDDEN, "차단된 계정입니다. 관리자에게 문의하세요"),
    // 소속 학원 밖 자원 요청(API_SPEC §1.5·§8.1) — 메인 관리자 콘솔(§6)만 예외다. 404 가 아니라 403 인
    // 이유는, 존재 여부를 알려 주지 않는 편이 안전해 보여도 사양이 격리 위반을 별도 코드로 구분하도록
    // 정했기 때문이다 — 클라이언트가 "없는 자원" 과 "남의 학원 자원" 을 구별해야 화면 문구가 갈린다.
    ACADEMY_SCOPE_VIOLATION(HttpStatus.FORBIDDEN, "소속 학원 밖 자원입니다"),
    // 미존재 계정 지정(API_SPEC §8.1) — 가입 상태 조회·재신청·본인 프로필 조회가 대상 계정을 못 찾을 때.
    ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "계정을 찾을 수 없습니다"),
    // 필수 필드 누락·형식 위반(API_SPEC §1.11) — Bean Validation·필수 쿼리 파라미터 부재가 공유한다.
    VALIDATION_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "입력값이 올바르지 않습니다"),
    // 회원가입(AUTH-01)에서 login_id 중복(API_SPEC §2.2).
    DUPLICATE_LOGIN_ID(HttpStatus.CONFLICT, "이미 사용 중인 아이디입니다"),
    // 회원가입·재신청이 존재하지 않거나 비활성인 학원을 가리킬 때(API_SPEC §2.2·§2.4).
    ACADEMY_NOT_FOUND(HttpStatus.NOT_FOUND, "학원을 찾을 수 없습니다"),
    // rejected 상태가 아닌 계정이 재신청을 시도할 때(API_SPEC §2.4).
    REAPPLY_NOT_ALLOWED(HttpStatus.CONFLICT, "재신청할 수 없는 상태입니다"),
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
