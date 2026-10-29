package src.backend.global.error;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.server.ResponseStatusException;

/** 프레임워크 예외가 응답으로 바뀌는 방식을 시험하는 전용 컨트롤러 — 테스트 소스에만 있다. */
@RestController
class FrameworkErrorTestController {

    @GetMapping("/framework-error-test/json")
    Map<String, Boolean> json() {
        return Map.of("ok", true);
    }

    @GetMapping("/framework-error-test/needs-header")
    String needsHeader(@RequestHeader("X-Needed") String needed) {
        return needed;
    }

    @GetMapping("/framework-error-test/status/{code}")
    String status(@PathVariable int code) {
        throw new ResponseStatusException(HttpStatus.valueOf(code), "사유 " + code);
    }

    @GetMapping("/framework-error-test/status-with-allow")
    String statusWithAllow() {
        org.springframework.http.HttpHeaders carried = new org.springframework.http.HttpHeaders();
        carried.setAllow(java.util.Set.of(org.springframework.http.HttpMethod.GET));
        throw new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED, "허용하지 않는 메서드") {
            @Override
            public org.springframework.http.HttpHeaders getHeaders() {
                return carried;
            }
        };
    }

    @GetMapping("/framework-error-test/client-abort")
    String clientAbort() throws AsyncRequestNotUsableException {
        throw new AsyncRequestNotUsableException("Response not usable after response errors");
    }
}
