package src.backend.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

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

import com.jayway.jsonpath.JsonPath;

/**
 * 회전으로 폐기된 refresh 토큰이 회전 10초 뒤에 다시 오면 훔친 토큰의 재사용으로 보고 그 계정의 refresh 토큰을 전부 폐기하며 감사 행
 * 1건을 남긴다. 10초 안의 재요청은 동시 요청 경합이라 그 요청만 401 이고, 로그아웃으로 폐기된 토큰은 재사용으로 보지 않는다.
 * 응답은 어느 경우에도 {@code 401 TOKEN_EXPIRED} 하나다.
 *
 * <p>{@code @Transactional} 이 부재하다 — 재사용 처리(전량 폐기 · 감사)는 401 을 던지는 요청 트랜잭션과 따로 커밋돼야 하므로,
 * 시험 트랜잭션이 감싸면 롤백이 가려진다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RefreshTokenReuseTest {

    private static final String LOGIN_ID = "refreuse01";

    private static final String PASSWORD = "reuse-password";

    private static final String REUSE_ACTION = "account.refresh_token_reuse";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private long accountId;

    @BeforeEach
    void 활성_계정을_만든다() {
        뒷정리한다();
        accountId = jdbcTemplate.queryForObject("""
                INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status)
                VALUES (1, ?, ?, '재사용계정', '010-0000-7003', 'parent', 'active') RETURNING id
                """, Long.class, LOGIN_ID, passwordEncoder.encode(PASSWORD));
    }

    @AfterEach
    void 뒷정리한다() {
        jdbcTemplate.update("DELETE FROM audit_log WHERE actor_login_id = ?", LOGIN_ID);
        jdbcTemplate.update("DELETE FROM account WHERE login_id = ?", LOGIN_ID);
    }

    @Test
    void 회전_10초_뒤_재사용하면_계정의_refresh_토큰을_전부_폐기하고_감사_행_1건을_남긴다() throws Exception {
        String original = 로그인해_refresh_토큰을_받는다();
        String rotated = 재발급해_새_refresh_토큰을_받는다(original);
        회전_시각을_과거로_민다(original, 11);

        재발급한다(original).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("TOKEN_EXPIRED"));

        assertThat(유효한_토큰_수()).as("재사용을 알아챈 뒤에는 회전으로 새로 받은 토큰도 남지 않는다").isZero();
        재발급한다(rotated).andExpect(status().isUnauthorized());
        assertThat(재사용_감사_행_수()).isEqualTo(1);
    }

    @Test
    void 회전_10초_안에_다시_오면_그_요청만_401_이고_다른_토큰은_폐기하지_않는다() throws Exception {
        String original = 로그인해_refresh_토큰을_받는다();
        String rotated = 재발급해_새_refresh_토큰을_받는다(original);

        재발급한다(original).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("TOKEN_EXPIRED"));

        assertThat(유효한_토큰_수()).as("동시 요청 경합은 세션 사슬을 건드리지 않는다").isEqualTo(1);
        재발급한다(rotated).andExpect(status().isOk());
        assertThat(재사용_감사_행_수()).isZero();
    }

    @Test
    void 로그아웃으로_폐기된_토큰이_나중에_다시_와도_다른_토큰은_폐기하지_않는다() throws Exception {
        String firstLogin = 로그인한다();
        String loggedOut = JsonPath.read(firstLogin, "$.data.refresh_token");
        String other = 로그인해_refresh_토큰을_받는다();
        로그아웃한다(firstLogin);
        jdbcTemplate.update("UPDATE refresh_token SET revoked_at = revoked_at - interval '1 hour' WHERE token_hash = ?",
                sha256Hex(loggedOut));

        재발급한다(loggedOut).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("TOKEN_EXPIRED"));

        assertThat(유효한_토큰_수()).isEqualTo(1);
        재발급한다(other).andExpect(status().isOk());
        assertThat(재사용_감사_행_수()).isZero();
    }

    private String 로그인해_refresh_토큰을_받는다() throws Exception {
        return JsonPath.read(로그인한다(), "$.data.refresh_token");
    }

    private String 로그인한다() throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login_id\":\"%s\",\"password\":\"%s\"}".formatted(LOGIN_ID, PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String 재발급해_새_refresh_토큰을_받는다(String refreshToken) throws Exception {
        String body = 재발급한다(refreshToken).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$.data.refresh_token");
    }

    private ResultActions 재발급한다(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refresh_token\":\"%s\"}".formatted(refreshToken)));
    }

    private void 로그아웃한다(String loginBody) throws Exception {
        String accessToken = JsonPath.read(loginBody, "$.data.access_token");
        String refreshToken = JsonPath.read(loginBody, "$.data.refresh_token");
        mockMvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refresh_token\":\"%s\"}".formatted(refreshToken)))
                .andExpect(status().isNoContent());
    }

    /** 서버가 보관하는 해시와 같은 값 — {@code token_hash} 로 행을 찾는 데 쓴다. */
    private static String sha256Hex(String rawToken) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 회전 시각을 {@code seconds} 초 전으로 민다 — 실제로 기다리지 않고 10초 창의 안과 밖을 가른다. */
    private void 회전_시각을_과거로_민다(String refreshToken, int seconds) {
        jdbcTemplate.update("UPDATE refresh_token SET revoked_at = revoked_at - make_interval(secs => ?), "
                + "rotated_at = rotated_at - make_interval(secs => ?) WHERE token_hash = ?",
                seconds, seconds, sha256Hex(refreshToken));
    }

    private int 유효한_토큰_수() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_token WHERE account_id = ? AND revoked_at IS NULL", Integer.class, accountId);
    }

    private int 재사용_감사_행_수() {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM audit_log WHERE target_id = ? AND category = 'data_access'
                   AND action = 'update' AND detail ->> 'action' = ?
                """, Integer.class, accountId, REUSE_ACTION);
    }
}
