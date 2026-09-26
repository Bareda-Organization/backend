package src.backend.global.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 라우팅되지 않는 요청 — 없는 경로 · 지원하지 않는 HTTP 메서드.
 *
 * <p><b>둘 다 클라이언트의 실수이지 서버 고장이 아니다.</b> 그런데 2026-09-20 실측에서 둘 다
 * {@code 500 INTERNAL_ERROR} 로 나갔다 — {@code GlobalExceptionHandler} 에 대응 처리기가 없어
 * catch-all 로 떨어졌기 때문이다. 그 응답은 <b>"주소를 잘못 불렀다" 와 "서버가 죽었다" 를 구별하지
 * 못하게 만든다</b> — 앱·웹이 재시도할지 고칠지 판단할 근거가 사라지고, 운영 알림도 오탐으로 는다.
 *
 * <p>인증을 붙이지 않는다 — 시큐리티가 먼저 막으면 라우팅 층까지 가지 않아 이 결함이 가려진다.
 * 두 경로 모두 {@code PublicEndpoints} 밖이지만, 디스패처가 핸들러를 못 찾는 판정이 인가보다
 * 앞서므로 그대로 드러난다.
 */
@SpringBootTest
@DisplayName("라우팅되지 않는 요청은 500 이 아니다")
class UnroutableRequestTest {

    private final MockMvc mockMvc;

    UnroutableRequestTest(@Autowired WebApplicationContext context) {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    @DisplayName("없는 경로는 404 ENDPOINT_NOT_FOUND")
    void 없는_경로는_404() throws Exception {
        mockMvc.perform(get("/api/v1/nope/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ENDPOINT_NOT_FOUND"));
    }

    @Test
    @DisplayName("지원하지 않는 메서드는 405 METHOD_NOT_ALLOWED")
    void 지원하지_않는_메서드는_405() throws Exception {
        // `POST /auth/login` 은 실재하는 핸들러다 — GET 으로 부르면 경로는 맞고 메서드만 틀리다.
        // 없는 경로(404)와 이 경우(405)를 가르지 못하면 클라이언트가 경로를 고칠지 메서드를
        // 고칠지 알 수 없다.
        mockMvc.perform(get("/api/v1/auth/login"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("실재하는 핸들러는 그대로 지난다 — 위 둘이 정상 요청까지 삼키지 않는다")
    void 실재하는_핸들러는_영향_부재() throws Exception {
        // 본문이 없어 422 든 400 이든 되지만, **404·405 는 아니어야** 한다.
        mockMvc.perform(post("/api/v1/auth/login").contentType("application/json").content("{}"))
                .andExpect(result -> {
                    int s = result.getResponse().getStatus();
                    assertThat(s)
                            .as("실재하는 핸들러가 404·405 로 떨어지면 새 처리기가 너무 넓게 잡은 것이다 — 실제 %s", s)
                            .isNotIn(404, 405);
                });
    }
}
