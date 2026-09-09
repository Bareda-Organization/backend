package src.backend.global.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * API_SPEC §1.2.1 — 웹은 refresh 토큰을 쿠키로만 주고받으므로 브라우저 요청이
 * {@code credentials: include} 로 나간다. 그 요청에는 응답에
 * {@code Access-Control-Allow-Credentials: true} 가 있어야 하고, 없으면 브라우저가
 * 응답을 통째로 버려 <b>웹 로그인 자체가 성립하지 않는다.</b>
 *
 * <p>이 검사가 없던 동안 실제로 그 헤더가 빠져 있었다(2026-09-10 F2 착수 시 발견).
 * curl 은 CORS 를 강제하지 않아 서버 응답만 보면 정상으로 보이고,
 * <b>실제 브라우저에서만</b> 드러나는 형태였다 — 그래서 여기서 헤더로 고정한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CorsCredentialsTest {

    /** 허용 출처 기본 목록(application.yml {@code app.cors.allowed-origins})에 들어 있는 값. */
    private static final String ALLOWED_ORIGIN = "http://localhost:3000";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 프리플라이트가_자격증명_허용을_알린다() throws Exception {
        mockMvc.perform(options("/api/v1/auth/login")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type,x-client-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void 실제_요청_응답에도_자격증명_허용이_붙는다() throws Exception {
        // 프리플라이트만 통과시키고 본 응답에서 빠뜨리면 브라우저는 여전히 응답을 버린다 —
        // 두 갈래를 따로 검사하는 이유다. 자격 증명이 틀려 401 이 나도 CORS 헤더는 붙어야 한다.
        mockMvc.perform(post("/api/v1/auth/login")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("X-Client-Type", "web")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login_id\":\"존재하지않는계정\",\"password\":\"x\"}"))
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }
}
