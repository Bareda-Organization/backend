package src.backend.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import src.backend.global.common.SeedFixtures;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * §5.22 {@code POST /staff/accounts/{accountId}/password-reset} — 관리자 경유 복구(AUTH-08 · Ruling 329 · BR-005).
 *
 * <p>SMS 연동 전까지 §2.9 가 {@code 503} 이라 학원 사용자의 유일한 복구 경로다. 대상은 같은 학원의
 * 학부모·학생·매니저만이고, 그 밖(타 학원·관계자·메인 관리자)은 존재를 드러내지 않는 {@code 404} 다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StaffAccountPasswordResetControllerTest {

    /** 시드 관계자 A(학원 A) 계정. */
    private static final long STAFF_A_ACCOUNT_ID = 2L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 관계자가_같은_학원_학부모의_비밀번호를_초기화하면_임시_비밀번호로_로그인되고_토큰이_끊긴다() throws Exception {
        long parentId = 계정_식별자(SeedFixtures.PARENT_A1_LOGIN_ID);
        jdbcTemplate.update("""
                INSERT INTO refresh_token (account_id, token_hash, issued_at, expires_at)
                VALUES (?, 'br005-live-token', now(), now() + interval '1 day')
                """, parentId);

        String body = mockMvc.perform(post("/api/v1/staff/accounts/" + parentId + "/password-reset")
                        .header("Authorization", 관계자_A_토큰()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.account_id").value(String.valueOf(parentId)))
                .andExpect(jsonPath("$.data.login_id").value(SeedFixtures.PARENT_A1_LOGIN_ID))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String temporaryPassword = JsonPath.read(body, "$.data.temporary_password");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login_id\":\"%s\",\"password\":\"%s\"}"
                                .formatted(SeedFixtures.PARENT_A1_LOGIN_ID, temporaryPassword)))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT revoked_at IS NOT NULL FROM refresh_token WHERE token_hash = 'br005-live-token'", Boolean.class))
                .as("옛 비밀번호로 얻은 세션이 살아 있으면 초기화의 목적이 소멸한다(C-14)").isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM audit_log
                WHERE action = 'update' AND target_type = 'account' AND target_id = ? AND actor_account_id = ?
                """, Integer.class, parentId, STAFF_A_ACCOUNT_ID))
                .as("초기화는 감사 기록 1행(action=update)").isEqualTo(1);
    }

    @Test
    void 초기화는_차단을_풀지_않는다() throws Exception {
        long blockedId = 계정_식별자(SeedFixtures.DRIVER_BLOCKED_LOGIN_ID);

        mockMvc.perform(post("/api/v1/staff/accounts/" + blockedId + "/password-reset")
                        .header("Authorization", 관계자_A_토큰()))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM account WHERE id = ?", String.class, blockedId))
                .isEqualTo("blocked");
    }

    @Test
    void 타_학원_계정과_관계자_계정은_404_ACCOUNT_NOT_FOUND_다() throws Exception {
        for (String loginId : new String[] {SeedFixtures.PARENT_B1_LOGIN_ID, SeedFixtures.STAFF_PENDING_LOGIN_ID,
                SeedFixtures.SYSTEM_ADMIN_LOGIN_ID}) {
            mockMvc.perform(post("/api/v1/staff/accounts/" + 계정_식별자(loginId) + "/password-reset")
                            .header("Authorization", 관계자_A_토큰()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("ACCOUNT_NOT_FOUND"));
        }
    }

    @Test
    void 관계자가_아니면_403_이다() throws Exception {
        long parentId = 계정_식별자(SeedFixtures.PARENT_A1_LOGIN_ID);
        String parentToken = "Bearer " + tokenProvider.createAccessToken(parentId, 1L, Role.PARENT, AccountStatus.ACTIVE);

        mockMvc.perform(post("/api/v1/staff/accounts/" + parentId + "/password-reset")
                        .header("Authorization", parentToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    private String 관계자_A_토큰() {
        return "Bearer " + tokenProvider.createAccessToken(STAFF_A_ACCOUNT_ID, 1L, Role.STAFF, AccountStatus.ACTIVE);
    }

    private long 계정_식별자(String loginId) {
        return jdbcTemplate.queryForObject("SELECT id FROM account WHERE login_id = ?", Long.class, loginId);
    }
}
