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
import org.springframework.transaction.annotation.Transactional;

/**
 * 50자를 넘는 <b>미등록</b> 로그인 아이디는 500 이 아니라 401 이다(BR-216 — BR-092 가 가입 쪽만 막았다).
 *
 * <p>미등록 분기는 가입 아이디 형식에 맞는 입력만 감사 행({@code actor_login_id varchar(50)})에 남긴다 — 50자를 넘는 입력은
 * 형식 밖이라 {@code null} 이고, 저장이 실패해 응답이 {@code 500} 이 되는 일도 없다. 등록될 수 없는 아이디라 401 이 열거 정보를
 * 더하지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LoginOverlongIdTest {

    private static final String LOGIN = "/api/v1/auth/login";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 오십자를_넘는_미등록_아이디는_401_이고_감사_행에_남기지_않는다() throws Exception {
        String overlong = "x".repeat(60);

        mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login_id\":\"%s\",\"password\":\"password\"}".formatted(overlong)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));

        String recorded = jdbcTemplate.queryForObject(
                "SELECT actor_login_id FROM audit_log WHERE action = 'login_fail' ORDER BY id DESC LIMIT 1",
                String.class);
        assertThat(recorded).as("가입 아이디 형식(50자 이하)이 아니므로 시도한 문자열을 남기지 않는다").isNull();
    }
}
