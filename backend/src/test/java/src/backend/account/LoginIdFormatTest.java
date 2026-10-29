package src.backend.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

    /** 이 시험이 만들기 전의 마지막 감사 행 번호 — 시험이 커밋한 login_fail 행만 세고 지운다. */
    private long baseAuditId;

    @BeforeEach
    void 시작_전_감사_행_번호를_잡는다() {
        baseAuditId = jdbcTemplate.queryForObject("SELECT COALESCE(max(id), 0) FROM audit_log", Long.class);
    }

    @AfterEach
    void 시험이_남긴_로그인_실패_행을_지운다() {
        jdbcTemplate.update("DELETE FROM audit_log WHERE action = 'login_fail' AND id > ?", baseAuditId);
    }

    @Test
    void 형식에_맞는_미등록_아이디는_감사_행에_그대로_남는다() throws Exception {
        로그인한다("typo.id-1", "password").andExpect(status().isUnauthorized());

        assertThat(새_실패_행의_아이디들()).as("새로 생긴 login_fail 행").containsExactly("typo.id-1");
    }

    @Test
    void 형식에_맞지_않는_미등록_입력은_감사_행에_남기지_않는다() throws Exception {
        로그인한다("비밀번호 잘못 넣음 pw!", "password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));

        assertThat(새_실패_행의_아이디들()).as("행은 하나 생기되 아이디 칸은 비어 있다 — 행이 아예 안 생긴 경우와 구별한다")
                .hasSize(1).containsOnlyNulls();
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

    private List<String> 새_실패_행의_아이디들() {
        return jdbcTemplate.query("SELECT actor_login_id FROM audit_log WHERE action = 'login_fail' AND id > ? ORDER BY id",
                (rs, row) -> rs.getString(1), baseAuditId);
    }
}
