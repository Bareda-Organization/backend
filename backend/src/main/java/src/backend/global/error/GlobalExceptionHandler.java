package src.backend.global.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;


import src.backend.global.response.ErrorResponse;
import src.backend.routing.map.spec.MapRouteUnavailableException;

/**
 * 모든 컨트롤러의 예외를 한 곳에서 처리한다.
 * 각 Controller 에 try-catch 를 흩뿌리지 않고, 일관된 에러 응답을 보장한다.
 *
 * <p>응답 본문은 {@link ErrorResponse}(§1.10) — {@code error.code} 에 {@link ErrorCode#name()} 을
 * 그대로 실어, 클라이언트가 문구가 아니라 이 값으로 {@code AUTH_PENDING}·{@code AUTH_REJECTED}·
 * {@code FORBIDDEN}(전부 403)을 구별한다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends RequestExceptionHandlers {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** DB 자원 오류 응답의 {@code Retry-After}(초) — 연결 대기 상한(3초)과 같은 주기로 다시 시도하게 한다. */
    private static final String DB_RETRY_AFTER_SECONDS = "3";

    /**
     * {@code details} 가 있으면(예: {@code INVALID_CREDENTIALS.remaining_attempts}, API_SPEC §2.5)
     * {@link ErrorResponse}의 3-인자 팩토리로 함께 싣는다 — 없으면 기존 2-인자 형태와 동일하다.
     *
     * <p>문구는 예외의 메시지다 — 호출부가 개별 사유를 주지 않았으면 코드 기본 문구와 같다. 개별
     * 사유("보호자 부재 보고는 rider_id 가 필수입니다")를 기본 문구로 덮으면 어느 칸이 틀렸는지가
     * 응답·로그 어디에도 남지 않는다(BR-135).
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        ErrorCode code = e.getErrorCode();
        ErrorResponse body = e.getDetails() == null
                ? ErrorResponse.of(code.name(), e.getMessage())
                : ErrorResponse.of(code.name(), e.getMessage(), e.getDetails());
        // SERVER_BUSY 는 DB 예외 핸들러 밖(인증 필터의 엔트리 포인트)에서도 BusinessException 으로 온다 — 같은 Retry-After 를 싣는다
        if (code == ErrorCode.SERVER_BUSY) {
            return ResponseEntity.status(code.getStatus()).header(HttpHeaders.RETRY_AFTER, DB_RETRY_AFTER_SECONDS).body(body);
        }
        return ResponseEntity.status(code.getStatus()).body(body);
    }

    /**
     * @PreAuthorize 인가 거부(AccessDeniedException). 아래 catch-all(Exception)보다 먼저 잡지 않으면
     * 권한 부족이 500 으로 뒤바뀐다 — 반드시 명시적으로 403 으로 변환한다.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException e) {
        ErrorCode code = ErrorCode.FORBIDDEN;
        return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code.name(), code.getMessage()));
    }

    /**
     * 외부 도로 경로 포트가 올린 {@link MapRouteUnavailableException} 을 {@code 503} 으로 옮긴다.
     *
     * <p>{@code GeocodingUnavailableException}(호출부 한 곳에서 번역)과 <b>다르게</b> 여기 두는
     * 이유는 이 포트의 호출부가 여럿이기 때문이다 — 확정 배치 · 승인 미리보기 · 경유 지점 지정이
     * 각자 번역하면 한 곳만 빠져도 아래 catch-all 로 떨어져 {@code 500} 이 나가고, 그 응답은
     * "지도 API 가 지금 안 된다" 와 "서버가 고장났다" 를 구별하지 못한다.
     *
     * <p>어댑터가 상태 코드를 정하지 않는다는 원칙은 그대로다 — 어댑터는 포트 예외만 던지고,
     * 코드를 정하는 것은 API 계층인 이 클래스다.
     */
    @ExceptionHandler(MapRouteUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleMapRouteUnavailable(MapRouteUnavailableException e) {
        ErrorCode code = ErrorCode.MAP_ROUTE_UNAVAILABLE;
        log.warn("[map-route] 도로 경로 조회 불가 {}", e.getMessage());
        return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code.name(), code.getMessage()));
    }

    /**
     * DB 자원 오류를 {@code 503 SERVER_BUSY} + {@code Retry-After} 로 옮긴다 — 어느 예외가 자원 오류인지는
     * {@link DbResourceFailure} 가 정한다(인증 필터도 같은 목록을 쓴다). 그 밖의 DB 예외는 코드 결함이라 {@link #handleUnexpected}
     * 의 {@code 500} + 스택이다.
     *
     * <p>로그는 스택 없는 {@code warn} 한 줄이다 — 풀이 마른 동안은 요청마다 이 예외가 나므로 스택을 남기면 같은 스택이
     * 분당 수백 건 쌓여 정작 원인 추적이 어렵다. 이 예외들은 코드 결함이 아니라 자원 상태라 스택이 알려 주는 것이 없다.
     * 교착 · 직렬화 실패는 잠금 순서가 어긋난 코드 결함이라 자원 오류에서 빠진다(BR-352 — {@link DbResourceFailure}).
     */
    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessException.class})
    public ResponseEntity<ErrorResponse> handleDbException(Exception e) {
        if (!DbResourceFailure.isResourceUnavailable(e)) {
            return handleUnexpected(e);
        }
        ErrorCode code = ErrorCode.SERVER_BUSY;
        log.warn("[db-unavailable] {} — {}", e.getClass().getSimpleName(), e.getMessage());
        return ResponseEntity.status(code.getStatus()).header(HttpHeaders.RETRY_AFTER, DB_RETRY_AFTER_SECONDS)
                .body(ErrorResponse.of(code.name(), code.getMessage()));
    }

    /**
     * 자기 HTTP 상태를 싣고 온 프레임워크 예외 — 받을 수 없는 {@code Accept}(406) · 필수 헤더 누락 같은 바인딩 오류(400) · 서버 안에서 상태를 지정해 던진
     * {@code ResponseStatusException}. 아래 catch-all 로 떨어지면 클라이언트 실수가 {@code 500} 과 ERROR 스택으로 보이므로 상태 분류에 맞는 코드로 답한다
     * ({@link StatusErrorCodes}). 예외가 싣고 온 응답 헤더(405 의 {@code Allow} 등)는 그대로 응답에 싣는다. 응답은 JSON 으로 고정한다 — 406 은 요청의 {@code Accept} 가 바로 응답 형식을 못 맞춘 경우라 협상에 맡기면 같은 오류가 되풀이된다.
     */
    @ExceptionHandler({HttpMediaTypeNotAcceptableException.class, ServletRequestBindingException.class,
            ResponseStatusException.class})
    public ResponseEntity<ErrorResponse> handleStatusCarrying(Exception e) {
        org.springframework.web.ErrorResponse carrier = (org.springframework.web.ErrorResponse) e;
        ErrorCode code = StatusErrorCodes.of(carrier.getStatusCode().value());
        if (code == ErrorCode.INTERNAL_ERROR) {
            return handleUnexpected(e);
        }
        log.warn("[request] {} {}", e.getClass().getSimpleName(), e.getMessage());
        return ResponseEntity.status(code.getStatus()).headers(carrier.getHeaders()).contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse.of(code.name(), code.getMessage()));
    }

    /** 클라이언트에겐 상세를 감추되, 서버 로그엔 스택트레이스를 남겨야 원인 추적이 가능하다. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("[unexpected] {}", e.getMessage(), e);
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code.name(), code.getMessage()));
    }
}
