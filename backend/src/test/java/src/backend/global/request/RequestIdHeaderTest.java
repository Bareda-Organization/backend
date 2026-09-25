package src.backend.global.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * API_SPEC §1.3 {@code X-Request-Id} — 받으면 그대로, 없으면 서버가 만들어 응답에 싣는다(BR-080).
 * 앱의 "처리되지 않았습니다"(C-10) 신고를 서버 로그의 그 요청과 잇는 유일한 끈이다.
 *
 * <p>인증 없이 401 로 끝나는 보호 경로를 쓴다 — 거부 응답에도 실려야 추적이 필요한 실패를 잇는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("X-Request-Id 는 받으면 되돌리고 없으면 만든다")
class RequestIdHeaderTest {

    private static final String HEADER = "X-Request-Id";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 보낸_식별자를_응답에_되돌린다() throws Exception {
        String echoed = mockMvc.perform(get("/api/v1/me").header(HEADER, "app-7f3a.c1_9"))
                .andReturn().getResponse().getHeader(HEADER);

        assertThat(echoed).isEqualTo("app-7f3a.c1_9");
    }

    @Test
    void 없으면_서버가_만들어_싣는다() throws Exception {
        String generated = mockMvc.perform(get("/api/v1/me")).andReturn().getResponse().getHeader(HEADER);

        assertThat(generated).isNotBlank();
    }

    /** 로그에 그대로 찍히는 값이라, 줄바꿈 같은 문자를 받아들이면 로그 줄을 꾸며 넣을 수 있다. */
    @Test
    void 허용하지_않는_모양이면_새로_만든다() throws Exception {
        String replaced = mockMvc.perform(get("/api/v1/me").header(HEADER, "a\tb" + "x".repeat(80)))
                .andReturn().getResponse().getHeader(HEADER);

        assertThat(replaced).isNotBlank().doesNotContain("\t").hasSizeLessThanOrEqualTo(64);
    }
}
