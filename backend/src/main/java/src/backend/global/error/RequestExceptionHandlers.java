package src.backend.global.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.servlet.http.HttpServletResponse;

import src.backend.global.response.ErrorResponse;

/**
 * 클라이언트가 잘못 보낸 요청(형식 위반 · 없는 주소 · 지원하지 않는 메서드 · 끊긴 연결)을 {@link ErrorResponse} 로 바꾸는 처리기 묶음 — {@link GlobalExceptionHandler} 가 상속해 한 어드바이스로
 * 동작한다. 어드바이스를 둘로 나누면 예외 처리기가 어드바이스 순서로 골라져 catch-all 이 가까운 처리기를 가릴 수 있어, 한 어드바이스 안의 상속으로 나눈다.
 */
abstract class RequestExceptionHandlers {

    /** 로거 이름은 {@link GlobalExceptionHandler} 로 둔다 — 처리기를 나눠도 로그 범주(운영 필터 · 시험의 로그 캡처)가 그대로다. */
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * {@code @Valid} 바디 검증 실패와 {@code @ModelAttribute} 바인딩 실패(API_SPEC §1.11 {@code VALIDATION_FAILED}, 422). 문구는 한국어이고 위반이 여럿이면
     * 필드명 순으로 첫 위반 하나를 싣는다({@link ValidationFailureMessage}).
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        ErrorCode code = ErrorCode.VALIDATION_FAILED;
        String message = ValidationFailureMessage.of(e.getBindingResult()).orElse(code.getMessage());
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
     * 누락 · multipart 엔드포인트에 다른 매체(JSON 등)를 보낸 요청(BR-032, §1.11 "형식 위반"). catch-all 로 떨어지면 {@code 500} 이 되어 클라이언트 실수가 서버
     * 고장으로 보이고, 오프라인 큐가 5xx 를 재시도 대상으로 봐 같은 요청을 끝없이 다시 보낸다.
     *
     * <p>Content-Type 누락도 {@code 415} 가 아니라 {@code 422} 다 — 사양의 에러 사전(§8)에 형식 위반
     * 코드는 {@code VALIDATION_FAILED} 하나뿐이다. 역직렬화 오류 문구는 내부 타입명을 담아 싣지 않는다.
     */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            HandlerMethodValidationException.class, HttpMediaTypeNotSupportedException.class,
            MultipartException.class})
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
     * {@link MultipartException} 의 하위라 위 형식 오류 목록보다 이 핸들러가 더 가까운 일치로 먼저 받는다.
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
        return ResponseEntity.status(code.getStatus()).headers(e.getHeaders()).body(ErrorResponse.of(code.name(), code.getMessage()));
    }

    /** 응답을 받기 전에 클라이언트가 끊은 요청 — 쓸 곳이 없으므로 본문을 쓰지 않고, 서버 결함이 아니라 ERROR 로 남기지 않는다. */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClientAbort(AsyncRequestNotUsableException e, HttpServletResponse response) {
        log.debug("[client-abort] {}", e.getMessage());
    }
}
