package src.backend.global.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * multipart 엔드포인트에 multipart 가 아닌 매체(JSON)를 보내면 {@code 500} 이 아니라
 * {@code 422 VALIDATION_FAILED} 다(API_SPEC §1.1) — {@code MultipartException} 이 catch-all 로 떨어지면
 * 클라이언트 실수가 서버 고장으로 보이고 오프라인 큐가 끝없이 재시도한다(BR-032).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MultipartMalformedRequestTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtTokenProvider tokenProvider;

    /**
     * MockMvc 는 컨테이너의 파트 파싱을 거치지 않아 이 예외를 못 낸다(그쪽은 {@code MissingServletRequestPartException}
     * 으로 이미 422) — 실제 HTTP 로 보내야 톰캣이 {@code MultipartException} 을 올린다.
     */
    @Test
    void multipart_PATCH_에_JSON_을_보내면_422_이다() throws Exception {
        HttpResponse<String> res = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/staff/students/999999"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer "
                                + tokenProvider.createAccessToken(1L, 1L, Role.STAFF, AccountStatus.ACTIVE))
                        .method("PATCH", BodyPublishers.ofString("{\"note\":\"x\"}")).build(),
                BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(422);
        assertThat(res.body()).contains("\"code\":\"VALIDATION_FAILED\"");
    }

    /**
     * {@code MaxUploadSizeExceededException} 은 {@code MultipartException} 의 하위다 — 부모를 목록에 넣어도
     * 더 가까운 크기 초과 핸들러가 계속 받아야 한다(문구로 구별). MockMvc 는 컨테이너 한도를 적용하지
     * 않아 던지는 컨트롤러를 두고 핸들러 선택만 본다.
     */
    @Test
    void 크기_초과는_여전히_전용_핸들러가_받는다() throws Exception {
        MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler()).build()
                .perform(get("/too-large"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.message").value("첨부 파일이 허용 크기를 넘었습니다"));
    }

    @RestController
    static class ThrowingController {
        @GetMapping("/too-large")
        String tooLarge() {
            throw new MaxUploadSizeExceededException(1L);
        }
    }
}
