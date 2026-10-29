package src.backend.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 로그인 아이디 형식({@code ^[A-Za-z0-9._-]{1,50}$}, Ruling 937 I) — 가입은 형식 밖 아이디를 거절하고, 미등록 아이디로 실패한
 * 로그인은 형식에 맞는 입력만 감사 행에 남긴다. 아이디 칸에 비밀번호를 잘못 넣은 경우가 2년 보존되는 감사 행에 남지 않게 한다.
 * 로그인 비밀번호는 가입과 같은 72바이트 상한을 받는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LoginIdFormatTest {

    private static final String LOGIN = "/api/v1/auth/login";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 형식에_맞는_미등록_아이디는_감사_행에_그대로_남는다() throws Exception {
        로그인한다("typo.id-1", "password").andExpect(status().isUnauthorized());

        assertThat(마지막_실패_행의_아이디()).isEqualTo("typo.id-1");
    }

    @Test
    void 형식에_맞지_않는_미등록_입력은_감사_행에_남기지_않는다() throws Exception {
        로그인한다("비밀번호 잘못 넣음 pw!", "password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));

        assertThat(마지막_실패_행의_아이디()).isNull();
    }

    @Test
    void 가입은_형식_밖_아이디를_422_로_거절한다() throws Exception {
        for (String loginId : new String[] {"has space", "한글아이디", "semi;colon", "a".repeat(51)}) {
            mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content("""
                            {"role": "parent", "login_id": "%s", "password": "password1234",
                             "name": "형식시험", "phone": "010-0000-7001", "academy_id": "1"}
                            """.formatted(loginId)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    void 로그인_비밀번호가_72바이트를_넘으면_422_이다() throws Exception {
        로그인한다("nobody.here", "a".repeat(73)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        로그인한다("nobody.here", "가".repeat(25)).andExpect(status().isUnprocessableContent());
    }

    @Test
    void 로그인_비밀번호가_72바이트이면_자격_대조까지_간다() throws Exception {
        로그인한다("nobody.here", "a".repeat(72)).andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.ResultActions 로그인한다(String loginId, String password)
            throws Exception {
        return mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"login_id\":\"%s\",\"password\":\"%s\"}".formatted(loginId, password)));
    }

    private String 마지막_실패_행의_아이디() {
        return jdbcTemplate.queryForObject(
                "SELECT actor_login_id FROM audit_log WHERE action = 'login_fail' ORDER BY id DESC LIMIT 1",
                String.class);
    }
}
