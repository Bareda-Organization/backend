package src.backend.global.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import src.backend.global.response.ErrorResponse;

/**
 * 모든 컨트롤러의 예외를 한 곳에서 처리한다.
 * 각 Controller 에 try-catch 를 흩뿌리지 않고, 일관된 에러 응답을 보장한다.
 *
 * <p>응답 본문은 {@link ErrorResponse}(§1.10) — {@code error.code} 에 {@link ErrorCode#name()} 을
 * 그대로 실어, 클라이언트가 문구가 아니라 이 값으로 {@code AUTH_PENDING}·{@code AUTH_REJECTED}·
 * {@code FORBIDDEN}(전부 403)을 구별한다(Phase 2 Task 2 리뷰 라운드 1 Important #6).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        ErrorCode code = e.getErrorCode();
        return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code.name(), code.getMessage()));
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
     * {@code @Valid} 바디 검증 실패(API_SPEC §1.11 {@code VALIDATION_FAILED}, 422) — 이 핸들러가
     * 이 저장소 최초의 {@code @Valid} DTO 를 다루는 소비자라(Phase 2 Task 3), 기존
     * {@code INVALID_INPUT}(400)을 참조하는 다른 코드가 없어 사양값으로 바로 맞췄다.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        ErrorCode code = ErrorCode.VALIDATION_FAILED;
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .orElse(code.getMessage());
        return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code.name(), message));
    }

    /**
     * 필수 {@code @RequestParam} 누락(예: {@code GET /academies/search} 의 {@code q}) — 손대지 않으면
     * 이 예외가 아래 catch-all 로 떨어져 422 가 아니라 500 으로 응답한다(API_SPEC §2.1 {@code VALIDATION_FAILED}).
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException e) {
        ErrorCode code = ErrorCode.VALIDATION_FAILED;
        return ResponseEntity.status(code.getStatus())
                .body(ErrorResponse.of(code.name(), e.getParameterName() + " 파라미터가 필요합니다"));
    }

    /** 클라이언트에겐 상세를 감추되, 서버 로그엔 스택트레이스를 남겨야 원인 추적이 가능하다. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("[unexpected] {}", e.getMessage(), e);
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code.name(), code.getMessage()));
    }
}
