package src.backend.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 가입과 복구가 같은 연락처 규칙을 쓴다(API_SPEC §2.2·§2.9) — 하이픈 있는 표기({@code 010-1234-5678})와 없는 표기
 * ({@code 01012345678})를 받고, 저장과 코드 발급은 하이픈 표준형 하나로 한다. 저장된 표기가 어느 쪽이든 복구가 계정을 찾는다.
 *
 * <p>{@code @Transactional} 을 쓰지 않는다 — 코드 행과 계정이 요청마다 자기 트랜잭션으로 커밋되는 경로를 그대로 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AccountRecoveryFlowTest.RecordingSmsConfig.class)
class PhoneRuleTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AccountRecoveryFlowTest.RecordingSmsSender smsSender;

    private int middle;

    private int last;

    private String hyphenated;

    private String digits;

    @BeforeEach
    void 번호를_정한다() {
        smsSender.clear();
        middle = ThreadLocalRandom.current().nextInt(1000, 10000);
        last = ThreadLocalRandom.current().nextInt(1000, 10000);
        hyphenated = "010-%d-%d".formatted(middle, last);
        digits = "010%d%d".formatted(middle, last);
    }

    @AfterEach
    void 뒷정리() {
        jdbcTemplate.update("DELETE FROM verification_code WHERE phone IN (?, ?)", hyphenated, digits);
        jdbcTemplate.update("DELETE FROM signup_request WHERE account_id IN "
                + "(SELECT id FROM account WHERE login_id LIKE 'phonerule.%')");
        jdbcTemplate.update("DELETE FROM account WHERE login_id LIKE 'phonerule.%'");
    }

    @Test
    void 복구는_하이픈_표기로_저장된_계정을_숫자만_입력해도_찾는다() throws Exception {
        계정을_심는다("phonerule.a", hyphenated);

        복구한다("login_id", digits).andExpect(status().isOk());

        assertThat(smsSender.sent()).hasSize(1);
        assertThat(코드_행_수(hyphenated)).as("코드 행은 표준형 번호로 쌓인다").isEqualTo(1);
    }

    @Test
    void 복구는_숫자만_저장된_계정을_하이픈_입력으로도_찾는다() throws Exception {
        계정을_심는다("phonerule.b", digits);

        복구한다("login_id", hyphenated).andExpect(status().isOk());

        assertThat(smsSender.sent()).hasSize(1);
    }

    @Test
    void 복구에서_두_표기는_같은_발급_한도를_나눠_쓴다() throws Exception {
        계정을_심는다("phonerule.c", hyphenated);
        복구한다("login_id", hyphenated).andExpect(status().isOk());

        복구한다("login_id", digits).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RECOVERY_RATE_LIMITED"));

        assertThat(smsSender.sent()).hasSize(1);
    }

    @Test
    void 복구는_연락처_형식이_아닌_입력을_422_로_거절한다() throws Exception {
        for (String phone : new String[] {"abc", "010-12", "123456789012", "010--1234-5678", "tel:01012345678"}) {
            복구한다("login_id", phone).andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    void 가입은_연락처를_하이픈_표준형으로_저장한다() throws Exception {
        가입한다("phonerule.d", digits).andExpect(status().isCreated());
        가입한다("phonerule.e", hyphenated).andExpect(status().isCreated());

        assertThat(저장된_연락처("phonerule.d")).isEqualTo(hyphenated);
        assertThat(저장된_연락처("phonerule.e")).isEqualTo(hyphenated);
    }

    @Test
    void 가입은_연락처_형식이_아닌_입력을_422_로_거절한다() throws Exception {
        for (String phone : new String[] {"abc", "010-12", "123456789012", "010-1234-56789", "전화번호"}) {
            가입한다("phonerule.f", phone).andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
    }

    private void 계정을_심는다(String loginId, String phone) {
        jdbcTemplate.update("INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status) "
                + "VALUES (1, ?, 'x', '연락처시험', ?, 'parent', 'active')", loginId, phone);
    }

    private ResultActions 복구한다(String type, String phone) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/recover").contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\": \"%s\", \"phone\": \"%s\"}".formatted(type, phone)));
    }

    private ResultActions 가입한다(String loginId, String phone) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content("""
                {"role": "parent", "login_id": "%s", "password": "password1234",
                 "name": "연락처시험", "phone": "%s", "academy_id": "1"}
                """.formatted(loginId, phone)));
    }

    private String 저장된_연락처(String loginId) {
        return jdbcTemplate.queryForObject("SELECT phone FROM account WHERE login_id = ?", String.class, loginId);
    }

    private int 코드_행_수(String phone) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM verification_code WHERE phone = ?", Integer.class,
                phone);
    }
}
