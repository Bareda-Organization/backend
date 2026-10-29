package src.backend.academy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static testsupport.auth.TestTokens.메인관리자_토큰;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;

import src.backend.global.security.JwtTokenProvider;

/** 관계자 계정의 연락처 수정(§6.7)도 가입·복구와 같은 연락처 규칙을 쓴다 — 두 표기를 받고 하이픈 표준형으로 저장한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StaffAccountPhoneRuleTest {

    private static final long SYSTEM_ADMIN_ACCOUNT_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private EntityManager entityManager;

    @Test
    void 숫자만_준_연락처는_하이픈_표준형으로_저장된다() throws Exception {
        long accountId = 관계자를_만든다("phonestaff1");

        수정한다(accountId, "01077771234").andExpect(status().isOk());

        assertThat(저장된_연락처(accountId)).isEqualTo("010-7777-1234");
    }

    @Test
    void 하이픈_표기_연락처는_그대로_표준형이다() throws Exception {
        long accountId = 관계자를_만든다("phonestaff2");

        수정한다(accountId, "010-7777-1234").andExpect(status().isOk());

        assertThat(저장된_연락처(accountId)).isEqualTo("010-7777-1234");
    }

    @Test
    void 연락처_형식이_아니면_422_이고_저장값은_그대로다() throws Exception {
        long accountId = 관계자를_만든다("phonestaff3");

        for (String phone : new String[] {"abc", "010-12", "010--7777-1234", "1234567"}) {
            수정한다(accountId, phone).andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }

        assertThat(저장된_연락처(accountId)).isEqualTo("010-0000-0000");
    }

    private long 관계자를_만든다(String loginId) {
        String code = loginId.toUpperCase();
        jdbcTemplate.update("INSERT INTO academy (code, name, region, status) VALUES (?, ?, '서울', 'active')", code,
                "연락처학원" + loginId);
        long academyId = jdbcTemplate.queryForObject("SELECT id FROM academy WHERE code = ?", Long.class, code);
        jdbcTemplate.update("INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status) "
                + "VALUES (?, ?, 'x', '연락처관계자', '010-0000-0000', 'staff', 'active')", academyId, loginId);
        long accountId = jdbcTemplate.queryForObject("SELECT id FROM account WHERE login_id = ?", Long.class, loginId);
        jdbcTemplate.update("INSERT INTO academy_staff (academy_id, account_id, status) VALUES (?, ?, 'active')",
                academyId, accountId);
        return accountId;
    }

    private ResultActions 수정한다(long accountId, String phone) throws Exception {
        return mockMvc.perform(patch("/api/v1/admin/staff-accounts/" + accountId)
                .header("Authorization", 메인관리자_토큰(tokenProvider, SYSTEM_ADMIN_ACCOUNT_ID))
                .contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"%s\"}".formatted(phone)));
    }

    private String 저장된_연락처(long accountId) {
        entityManager.flush();
        entityManager.clear();
        return jdbcTemplate.queryForObject("SELECT phone FROM account WHERE id = ?", String.class, accountId);
    }
}
