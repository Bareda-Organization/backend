package src.backend.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static testsupport.auth.TestTokens.메인관리자_토큰;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManager;

import src.backend.global.security.JwtTokenProvider;

/**
 * 퇴원 보존 기간이 지나 익명화된 학생 계정(차단 사유 "퇴원 90일 경과 개인정보 파기")은 차단 계정 목록·건수에 나오지 않고
 * 해제할 수 없다(§6.10·§6.12) — 로그인 실패로 차단된 계정만 관리자가 푸는 대상이다. 익명화 계정을 해제하면 지워 둔 개인정보 자리의
 * 계정이 되살아난다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RetentionBlockedAccountTest {

    private static final long SYSTEM_ADMIN_ACCOUNT_ID = 1L;

    private static final String RETENTION_REASON = "퇴원 90일 경과 개인정보 파기";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private EntityManager entityManager;

    @Test
    void 익명화된_계정은_차단_목록과_건수에서_빠진다() throws Exception {
        int listedBefore = 목록_건수();
        long blockedBefore = 대시보드_차단_건수();

        차단_계정을_심는다("retention.loginfail", "로그인 실패 5회 누적(C-11)");
        차단_계정을_심는다("retention.purged", RETENTION_REASON);

        String body = 목록_본문();
        assertThat(로그인_아이디들(body)).contains("retention.loginfail").doesNotContain("retention.purged");
        assertThat(((Number) JsonPath.read(body, "$.data.total_count")).intValue()).isEqualTo(listedBefore + 1);
        assertThat(대시보드_차단_건수()).as("대시보드 attention.blocked_accounts").isEqualTo(blockedBefore + 1);
    }

    @Test
    void 사유가_없는_차단_계정은_목록과_건수에_센다() throws Exception {
        int listedBefore = 목록_건수();
        long blockedBefore = 대시보드_차단_건수();

        차단_계정을_심는다("retention.noreason", null);

        assertThat(로그인_아이디들(목록_본문())).contains("retention.noreason");
        assertThat(목록_건수()).isEqualTo(listedBefore + 1);
        assertThat(대시보드_차단_건수()).isEqualTo(blockedBefore + 1);
    }

    @Test
    void 익명화된_계정을_해제하면_409_ACCOUNT_NOT_BLOCKED_이고_차단은_그대로다() throws Exception {
        long purged = 차단_계정을_심는다("retention.purged2", RETENTION_REASON);

        mockMvc.perform(post("/api/v1/admin/blocked-accounts/" + purged + "/unblock")
                        .header("Authorization", 메인관리자_토큰(tokenProvider, SYSTEM_ADMIN_ACCOUNT_ID)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_NOT_BLOCKED"));

        entityManager.flush();
        entityManager.clear();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM account WHERE id = ?", String.class, purged))
                .isEqualTo("blocked");
    }

    @Test
    void 로그인_실패로_차단된_계정은_계속_해제된다() throws Exception {
        long blocked = 차단_계정을_심는다("retention.loginfail2", "로그인 실패 5회 누적(C-11)");

        mockMvc.perform(post("/api/v1/admin/blocked-accounts/" + blocked + "/unblock")
                        .header("Authorization", 메인관리자_토큰(tokenProvider, SYSTEM_ADMIN_ACCOUNT_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.account_status").value("active"));
    }

    private long 차단_계정을_심는다(String loginId, String reason) {
        jdbcTemplate.update("INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status, "
                + "status_before_block, blocked_at, block_reason, failed_attempts) "
                + "VALUES (1, ?, 'x', '차단시험', '010-0000-0000', 'student', 'blocked', 'active', now(), ?, 5)",
                loginId, reason);
        return jdbcTemplate.queryForObject("SELECT id FROM account WHERE login_id = ?", Long.class, loginId);
    }

    private String 목록_본문() throws Exception {
        entityManager.flush();
        entityManager.clear();
        return mockMvc.perform(get("/api/v1/admin/blocked-accounts?size=100")
                        .header("Authorization", 메인관리자_토큰(tokenProvider, SYSTEM_ADMIN_ACCOUNT_ID)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private int 목록_건수() throws Exception {
        return ((Number) JsonPath.read(목록_본문(), "$.data.total_count")).intValue();
    }

    private List<String> 로그인_아이디들(String body) {
        return JsonPath.read(body, "$.data.items[*].login_id");
    }

    private long 대시보드_차단_건수() throws Exception {
        entityManager.flush();
        entityManager.clear();
        String body = mockMvc.perform(get("/api/v1/admin/dashboard?days=7")
                        .header("Authorization", 메인관리자_토큰(tokenProvider, SYSTEM_ADMIN_ACCOUNT_ID)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return ((Number) JsonPath.read(body, "$.data.attention.blocked_accounts")).longValue();
    }
}
