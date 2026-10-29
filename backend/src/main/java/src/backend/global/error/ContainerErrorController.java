package src.backend.global.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

import src.backend.global.response.ErrorResponse;

/**
 * 스프링 MVC 밖에서 난 오류(컨테이너가 거절한 잘못된 요청 · 방화벽이 막은 주소)가 컨테이너를 거쳐 도착하는 {@code /error} — 기본 오류 본문 대신 다른 모든 오류와 같은
 * {@link ErrorResponse} 로 답한다. 인증 없이 열려 있으므로({@code SecurityConfig}) 원래 오류가 {@code 401} 로 바뀌지 않는다. {@code @RestController} 가 아니라 API 접두사가
 * 붙지 않고, 요청의 {@code Accept} 와 무관하게 JSON 으로 답한다.
 */
@Hidden
@Controller
public class ContainerErrorController implements ErrorController {

    private static final Logger log = LoggerFactory.getLogger(ContainerErrorController.class);

    /**
     * 컨테이너가 남긴 상태를 {@link StatusErrorCodes} 의 코드로 옮겨 답한다. 상태 속성이 없으면 컨테이너가 넘긴 요청이 아니라 누군가 {@code /error} 를 직접 부른 것이다 — 이 경로는
     * 인증 없이 열려 있어 서버 오류로 답하면 호출마다 경고 로그와 가짜 500 이 쌓이므로, 로그 없이 없는 엔드포인트와 같은 {@code 404} 로 답한다.
     */
    @RequestMapping("/error")
    @ResponseBody
    public ResponseEntity<ErrorResponse> error(HttpServletRequest request) {
        if (!(request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer status)) {
            return respond(StatusErrorCodes.of(404));
        }
        ErrorCode errorCode = StatusErrorCodes.of(status);
        log.warn("[container-error] status={} uri={}", status, request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI));
        return respond(errorCode);
    }

    private static ResponseEntity<ErrorResponse> respond(ErrorCode errorCode) {
        return ResponseEntity.status(errorCode.getStatus()).contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse.of(errorCode.name(), errorCode.getMessage()));
    }
}
