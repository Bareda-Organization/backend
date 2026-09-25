package src.backend.global.error;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import src.backend.global.response.ErrorResponse;
import src.backend.routing.map.spec.MapRouteUnavailableException;

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
     * {@code @Valid} 바디 검증 실패(API_SPEC §1.11 {@code VALIDATION_FAILED}, 422) — 이 핸들러가
     * 이 저장소 최초의 {@code @Valid} DTO 를 다루는 소비자라(Phase 2 Task 3), 기존
     * {@code INVALID_INPUT}(400)을 참조하는 다른 코드가 없어 사양값으로 바로 맞췄다.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        ErrorCode code = ErrorCode.VALIDATION_FAILED;
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> snakeCase(err.getField()) + ": " + err.getDefaultMessage())
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

    /**
     * 멀티파트 필수 파트 누락(학생 사진 업로드의 JSON 파트 {@code data}, §5.11) — 손대지 않으면
     * 아래 catch-all 로 떨어져 {@code 422} 가 아니라 {@code 500} 으로 응답한다.
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ErrorResponse> handleMissingPart(MissingServletRequestPartException e) {
        ErrorCode code = ErrorCode.VALIDATION_FAILED;
        return ResponseEntity.status(code.getStatus())
                .body(ErrorResponse.of(code.name(), e.getRequestPartName() + " 파트가 필요합니다"));
    }

    /**
     * 요청 형식 오류 — 깨진 JSON · enum 밖 값 · 경로/쿼리 타입 불일치 · 파라미터 제약 위반 · Content-Type
     * 누락(BR-032, §1.11 "형식 위반"). catch-all 로 떨어지면 {@code 500} 이 되어 클라이언트 실수가 서버
     * 고장으로 보이고, 오프라인 큐가 5xx 를 재시도 대상으로 봐 같은 요청을 끝없이 다시 보낸다.
     *
     * <p>Content-Type 누락도 {@code 415} 가 아니라 {@code 422} 다 — 사양의 에러 사전(§8)에 형식 위반
     * 코드는 {@code VALIDATION_FAILED} 하나뿐이다. 역직렬화 오류 문구는 내부 타입명을 담아 싣지 않는다.
     */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            HandlerMethodValidationException.class, HttpMediaTypeNotSupportedException.class})
    public ResponseEntity<ErrorResponse> handleMalformedRequest(Exception e) {
        ErrorCode code = ErrorCode.VALIDATION_FAILED;
        log.warn("[request] 형식 오류 {}", e.getMessage());
        String message = e instanceof MethodArgumentTypeMismatchException mismatch
                ? mismatch.getName() + " 값의 형식이 올바르지 않습니다"
                : code.getMessage();
        return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code.name(), message));
    }

    /**
     * 업로드 크기가 컨테이너 방어선({@code spring.servlet.multipart.max-file-size})을 넘은 경우.
     *
     * <p>사양 상한 5MB 는 그보다 낮아 사진 검증이 먼저 잡는다(§1.1) — 이 핸들러가 무는 것은 그
     * 방어선까지 넘긴 요청이고, 그때도 {@code 500} 이 아니라 같은 {@code 422} 여야 클라이언트가
     * "파일이 너무 큽니다" 를 한 갈래로 표시한다.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleUploadTooLarge(MaxUploadSizeExceededException e) {
        ErrorCode code = ErrorCode.VALIDATION_FAILED;
        log.warn("[upload] 허용 크기 초과 {}", e.getMessage());
        return ResponseEntity.status(code.getStatus())
                .body(ErrorResponse.of(code.name(), "첨부 파일이 허용 크기를 넘었습니다"));
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
     * 없는 주소 · 지원하지 않는 메서드 — 라우팅이 실패한 요청.
     *
     * <p>아래 catch-all 이 {@code 500} 으로 삼키던 자리다. <b>클라이언트의 실수를 서버 고장으로
     * 보이게 만들면</b> 부르는 쪽이 재시도할지 주소를 고칠지 판단할 수 없고, 운영 알림도 오탐으로
     * 는다. 로그도 {@code error} 가 아니라 {@code warn} 이다 — 서버가 할 일은 없다.
     *
     * <p>{@code NoResourceFoundException} 은 정적 자원 탐색 실패,
     * {@code NoHandlerFoundException} 은 핸들러 매핑 실패다. 설정에 따라 어느 쪽이 던져질지
     * 갈리므로 둘 다 받는다.
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(Exception e) {
        ErrorCode code = ErrorCode.ENDPOINT_NOT_FOUND;
        log.warn("[routing] 없는 주소 {}", e.getMessage());
        return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code.name(), code.getMessage()));
    }

    /** 경로는 실재하나 메서드가 다른 요청 — 404 와 갈라야 어느 쪽을 고칠지 알 수 있다. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        ErrorCode code = ErrorCode.METHOD_NOT_ALLOWED;
        log.warn("[routing] 지원하지 않는 메서드 {}", e.getMessage());
        return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code.name(), code.getMessage()));
    }

    /** 자바 필드 경로({@code newStudent.name})를 요청 본문의 키 표기({@code new_student.name}, Ruling 104)로 옮긴다. */
    private static String snakeCase(String field) {
        return field.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    /** 클라이언트에겐 상세를 감추되, 서버 로그엔 스택트레이스를 남겨야 원인 추적이 가능하다. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("[unexpected] {}", e.getMessage(), e);
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code.name(), code.getMessage()));
    }
}
