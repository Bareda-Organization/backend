package src.backend.global.error;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 스프링 MVC 밖에서 나는 오류(컨테이너의 잘못된 요청 · 방화벽이 거절한 주소)는 컨테이너가 {@code /error} 로 넘긴다. 그 경로가 인증을 요구하면 클라이언트는 원래 오류 대신 {@code 401} 을 받고,
 * 기본 오류 본문은 {@code ErrorResponse} 가 아니다. 또 브라우저 클라이언트가 읽도록 {@code Retry-After} 가 CORS 노출 헤더에 들어 있다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ErrorEndpointTest {

    private static final String STATUS_ATTR = "jakarta.servlet.error.status_code";

    @Autowired
    private MockMvc mockMvc;

    @ParameterizedTest(name = "컨테이너 상태 {0} → {1} {2}")
    @CsvSource({"400,422,VALIDATION_FAILED", "404,404,ENDPOINT_NOT_FOUND", "405,405,METHOD_NOT_ALLOWED",
            "500,500,INTERNAL_ERROR", "502,500,INTERNAL_ERROR"})
    @DisplayName("인증 없이 /error 로 온 오류는 ErrorResponse JSON 이다")
    void 오류_경로는_ErrorResponse_를_쓴다(int containerStatus, int expectedStatus, String expectedCode) throws Exception {
        mockMvc.perform(get("/error").requestAttr(STATUS_ATTR, containerStatus).accept(MediaType.TEXT_HTML))
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error.code").value(expectedCode))
                .andExpect(jsonPath("$.error.message").isString());
    }

    @Test
    @DisplayName("오류 상태 속성이 없는 /error 요청은 500 INTERNAL_ERROR")
    void 상태_속성이_없으면_500() throws Exception {
        mockMvc.perform(get("/error"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
    }

    @Test
    @DisplayName("POST 로 온 오류 전달도 같은 본문이다")
    void POST_전달도_같은_본문() throws Exception {
        mockMvc.perform(post("/error").requestAttr(STATUS_ATTR, 404))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ENDPOINT_NOT_FOUND"));
    }

    @Test
    @DisplayName("CORS 응답은 Retry-After 와 요청 추적 헤더를 브라우저에 노출한다")
    void CORS_노출_헤더에_Retry_After_가_있다() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login").header("Origin", "http://localhost:3000")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"login_id\":\"x\",\"password\":\"x\"}"))
                .andExpect(header().string("Access-Control-Expose-Headers", containsString("Retry-After")))
                .andExpect(header().string("Access-Control-Expose-Headers", containsString("X-Request-Id")));
    }
}
