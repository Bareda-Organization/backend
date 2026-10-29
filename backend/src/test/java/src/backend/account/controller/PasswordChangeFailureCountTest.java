package src.backend.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
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

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * 비밀번호 변경에서 현재 비밀번호를 틀리면 로그인 실패와 같은 카운터(C-11)에 쌓이고, 5회째에 계정이 차단된다(API_SPEC §2.8).
 * 로그인에만 횟수 제한을 두면 접근 토큰을 가진 사람이 변경 API 로 현재 비밀번호를 제한 없이 대입할 수 있다.
 *
 * <p>{@code @Transactional} 을 쓰지 않는다 — 실패 횟수가 응답 예외와 별개로 커밋되는지가 이 시험의 일부다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PasswordChangeFailureCountTest {

    private static final String LOGIN_ID = "pwchange.count";

    private static final String PASSWORD = "current-password-1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtTokenProvider tokenProvider;

    private long accountId;

    @BeforeEach
    void 계정을_만든다() {
        뒷정리();
        jdbcTemplate.update("INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status) "
                + "VALUES (1, ?, ?, '변경시험', '010-0000-0000', 'parent', 'active')", LOGIN_ID,
                passwordEncoder.encode(PASSWORD));
        accountId = jdbcTemplate.queryForObject("SELECT id FROM account WHERE login_id = ?", Long.class, LOGIN_ID);
    }

    @AfterEach
    void 뒷정리() {
        jdbcTemplate.update("DELETE FROM audit_log WHERE actor_login_id = ?", LOGIN_ID);
        jdbcTemplate.update("DELETE FROM refresh_token WHERE account_id IN (SELECT id FROM account WHERE login_id = ?)",
                LOGIN_ID);
        jdbcTemplate.update("DELETE FROM account WHERE login_id = ?", LOGIN_ID);
    }

    @Test
    void 현재_비밀번호를_틀리면_실패_횟수가_쌓이고_남은_횟수를_알린다() throws Exception {
        변경한다("wrong-1").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.error.details.remaining_attempts").value(4));
        변경한다("wrong-2").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.details.remaining_attempts").value(3));

        assertThat(실패_횟수()).isEqualTo(2);
        assertThat(상태()).isEqualTo("active");
    }

    @Test
    void 다섯_번_틀리면_로그인_실패와_같이_계정이_차단된다() throws Exception {
        for (int attempt = 1; attempt <= 4; attempt++) {
            변경한다("wrong-" + attempt).andExpect(status().isUnauthorized());
        }

        변경한다("wrong-5").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AUTH_ACCOUNT_BLOCKED"));

        assertThat(상태()).isEqualTo("blocked");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log WHERE actor_login_id = ? AND action = 'block'", Integer.class,
                LOGIN_ID)).as("차단 감사 행").isEqualTo(1);
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login_id\":\"%s\",\"password\":\"%s\"}".formatted(LOGIN_ID, PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AUTH_ACCOUNT_BLOCKED"));
    }

    @Test
    void 로그인_실패와_변경_실패는_한_카운터에_합쳐진다() throws Exception {
        for (int attempt = 1; attempt <= 3; attempt++) {
            mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"login_id\":\"%s\",\"password\":\"wrong\"}".formatted(LOGIN_ID)))
                    .andExpect(status().isUnauthorized());
        }
        변경한다("wrong-1").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.details.remaining_attempts").value(1));
        변경한다("wrong-2").andExpect(status().isForbidden());

        assertThat(상태()).isEqualTo("blocked");
    }

    @Test
    void 현재_비밀번호가_맞으면_변경되고_카운터를_올리지_않는다() throws Exception {
        변경한다(PASSWORD).andExpect(status().isNoContent());

        assertThat(실패_횟수()).isZero();
    }

    private ResultActions 변경한다(String currentPassword) throws Exception {
        String token = "Bearer " + tokenProvider.createAccessToken(accountId, 1L, Role.PARENT, AccountStatus.ACTIVE);
        return mockMvc.perform(post("/api/v1/auth/password").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"current_password\":\"%s\",\"new_password\":\"new-password-1234\"}"
                        .formatted(currentPassword)));
    }

    private int 실패_횟수() {
        return jdbcTemplate.queryForObject("SELECT failed_attempts FROM account WHERE id = ?", Integer.class,
                accountId);
    }

    private String 상태() {
        return jdbcTemplate.queryForObject("SELECT status FROM account WHERE id = ?", String.class, accountId);
    }
}
