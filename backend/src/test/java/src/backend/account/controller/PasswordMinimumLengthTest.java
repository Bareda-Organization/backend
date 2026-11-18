package src.backend.account.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;

/**
 * 사용자가 새 비밀번호를 정하는 두 경로(가입 · 비밀번호 변경)는 8자 미만을 {@code 422 VALIDATION_FAILED} 로 거절하고 8자는
 * 받는다. 로그인은 최소 길이를 보지 않으므로 규칙 이전에 만든 짧은 비밀번호의 계정도 그대로 들어온다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PasswordMinimumLengthTest {

    private static final String LOGIN_ID = "pwminlogin01";

    private static final String OLD_PASSWORD = "oldpass8";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private long academyId;

    @BeforeEach
    void 학원과_활성_계정을_만든다() {
        academyId = academyRepository.save(Academy.register("PWMIN000001", "비밀번호규칙학원", "서울", null, null)).getId();
        jdbcTemplate.update("""
                INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status)
                VALUES (?, ?, ?, '규칙계정', '010-0000-7001', 'parent', 'active')
                """, academyId, LOGIN_ID, passwordEncoder.encode(OLD_PASSWORD));
    }

    @Test
    void 가입은_비밀번호_7자를_422_로_거절한다() throws Exception {
        가입한다("pwminsign0701", "abcd123")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.message", startsWith("password:")))
                .andExpect(jsonPath("$.error.message", containsString("8자 이상")));
    }

    @Test
    void 가입은_비밀번호_8자를_받는다() throws Exception {
        가입한다("pwminsign0801", "abcd1234").andExpect(status().isCreated());
    }

    @Test
    void 가입은_글자_종류와_무관하게_8자면_받는다() throws Exception {
        가입한다("pwminsign0802", "가나다라마바사아").andExpect(status().isCreated());
    }

    @Test
    void 비밀번호_변경은_새_비밀번호_7자를_422_로_거절한다() throws Exception {
        비밀번호를_바꾼다(로그인해_액세스_토큰을_받는다(OLD_PASSWORD), "abcd123")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.message", startsWith("new_password:")))
                .andExpect(jsonPath("$.error.message", containsString("8자 이상")));
    }

    @Test
    void 비밀번호_변경은_새_비밀번호_8자를_받는다() throws Exception {
        비밀번호를_바꾼다(로그인해_액세스_토큰을_받는다(OLD_PASSWORD), "abcd1234").andExpect(status().isNoContent());
    }

    @Test
    void 로그인은_규칙_이전의_짧은_비밀번호로도_성공한다() throws Exception {
        jdbcTemplate.update("UPDATE account SET password_hash = ? WHERE login_id = ?",
                passwordEncoder.encode("short1"), LOGIN_ID);

        로그인한다("short1").andExpect(status().isOk());
    }

    private ResultActions 가입한다(String loginId, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content("""
                {"role": "parent", "login_id": "%s", "password": "%s", "name": "가입규칙",
                 "phone": "010-0000-7002", "academy_id": "%d"}
                """.formatted(loginId, password, academyId)));
    }

    private ResultActions 로그인한다(String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"login_id\": \"%s\", \"password\": \"%s\"}".formatted(LOGIN_ID, password)));
    }

    private String 로그인해_액세스_토큰을_받는다(String password) throws Exception {
        String body = 로그인한다(password).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$.data.access_token");
    }

    private ResultActions 비밀번호를_바꾼다(String accessToken, String newPassword) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/password").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"current_password\": \"%s\", \"new_password\": \"%s\"}".formatted(OLD_PASSWORD, newPassword)));
    }
}
