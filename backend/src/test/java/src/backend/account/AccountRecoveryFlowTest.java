package src.backend.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.global.common.enums.Role;
import src.backend.global.sms.spec.SmsSender;

/**
 * AUTH-08 전화번호 복구 재개(API_SPEC §2.9 · Ruling 513) — 문자 발송기가 있을 때의 동작이다. 발송기가 없을 때의
 * {@code 503} 은 {@code AuthControllerTest} 가 이미 본다.
 *
 * <p>{@code @Transactional} 을 쓰지 않는다 — 틀린 코드의 대조 횟수가 요청이 끝나는 커밋 뒤에도 남는지(상한이 실제로
 * 막는지)가 이 시험의 일부라, 시험 전체를 한 트랜잭션에 묶으면 롤백 누락 결함이 보이지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AccountRecoveryFlowTest.RecordingSmsConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class AccountRecoveryFlowTest {

    private static final String OLD_PASSWORD = "old-password-1234!";

    private static final Pattern SIX_DIGITS = Pattern.compile("\\d{6}");

    private static final Pattern TEMP_PASSWORD = Pattern.compile("임시 비밀번호 (\\S+)");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RecordingSmsSender smsSender;

    private String phone;

    private String loginId;

    private Long academyId;

    @BeforeEach
    void setUp() {
        smsSender.clear();
        int suffix = ThreadLocalRandom.current().nextInt(10_000_000, 100_000_000);
        phone = "010-" + suffix;
        loginId = "r46rec" + suffix;
        academyId = academyRepository.save(Academy.register("R46R" + suffix, "복구시험학원" + suffix, "서울", null, null))
                .getId();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM verification_code WHERE phone = ?", phone);
        jdbcTemplate.update("DELETE FROM refresh_token WHERE account_id IN (SELECT id FROM account WHERE academy_id = ?)",
                academyId);
        jdbcTemplate.update("DELETE FROM account WHERE academy_id = ?", academyId);
        jdbcTemplate.update("DELETE FROM academy WHERE id = ?", academyId);
    }

    /** 코드는 문자로만 나간다 — 응답 본문에 6자리 숫자가 있으면 발송 없이도 코드를 얻는 경로가 된다. */
    @Test
    void 코드_요청은_문자로_6자리를_보내고_응답에는_싣지_않는다() throws Exception {
        createAccount(Role.PARENT);

        String body = recover("password", null).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();

        assertThat(smsSender.sent()).hasSize(1);
        assertThat(smsSender.sent().get(0).phone()).isEqualTo(phone);
        assertThat(smsSender.sent().get(0).text()).contains(issuedCode());
        assertThat(SIX_DIGITS.matcher(body).find()).as("응답 본문").isFalse();
    }

    /** 번호당 60초에 1회 — 막지 않으면 문자 비용과 대조 상한 우회(재발급으로 새 5회)가 된다. */
    @Test
    void 같은_번호는_60초_안에_다시_발급하지_않는다() throws Exception {
        createAccount(Role.PARENT);
        recover("password", null).andExpect(status().isOk());

        recover("password", null).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RECOVERY_RATE_LIMITED"));

        assertThat(smsSender.sent()).hasSize(1);
        assertThat(codeRows()).isEqualTo(1);
    }

    /** 24시간에 5회 — 5건이 이미 있으면 6번째는 60초가 지났어도 거절한다. */
    @Test
    void 하루_5회를_넘기면_발급하지_않는다() throws Exception {
        createAccount(Role.PARENT);
        insertOldCodes(5);

        recover("password", null).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RECOVERY_RATE_LIMITED"));

        assertThat(smsSender.sent()).isEmpty();
    }

    /** 경계 — 4건 뒤의 5번째는 허용한다. */
    @Test
    void 하루_4회_뒤의_5번째_발급은_허용한다() throws Exception {
        createAccount(Role.PARENT);
        insertOldCodes(4);

        recover("password", null).andExpect(status().isOk());

        assertThat(smsSender.sent()).hasSize(1);
    }

    /** 임시 비밀번호는 문자로만 간다 — 맞는 코드는 한 번만 쓰이고, 받은 값으로 로그인이 된다. */
    @Test
    void 맞는_코드는_임시_비밀번호를_문자로만_주고_코드는_한_번만_쓰인다(CapturedOutput output) throws Exception {
        createAccount(Role.PARENT);
        recover("password", null).andExpect(status().isOk());
        String code = issuedCode();

        String body = recover("password", code).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();

        assertThat(smsSender.sent()).hasSize(2);
        String text = smsSender.sent().get(1).text();
        Matcher temp = TEMP_PASSWORD.matcher(text);
        assertThat(temp.find()).as("문자 본문에 임시 비밀번호").isTrue();
        String tempPassword = temp.group(1);
        assertThat(body).doesNotContain(tempPassword);
        login(OLD_PASSWORD).andExpect(status().isUnauthorized());
        login(tempPassword).andExpect(status().isOk());
        recover("password", code).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("VERIFICATION_CODE_INVALID"));
        assertThat(output.getAll()).doesNotContain(code).doesNotContain(tempPassword).doesNotContain(phone);
    }

    /** 틀린 코드 5회로 그 코드는 소진된다 — 6번째에 맞는 값을 넣어도 통과하지 않고, 비밀번호는 그대로다. */
    @Test
    void 틀린_코드_5회_뒤에는_맞는_코드도_통과하지_않는다() throws Exception {
        createAccount(Role.PARENT);
        recover("password", null).andExpect(status().isOk());
        String code = issuedCode();
        String wrong = code.equals("000000") ? "000001" : "000000";

        for (int attempt = 0; attempt < 5; attempt++) {
            recover("password", wrong).andExpect(status().isForbidden());
        }
        recover("password", code).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("VERIFICATION_CODE_INVALID"));

        assertThat(smsSender.sent()).hasSize(1);
        login(OLD_PASSWORD).andExpect(status().isOk());
    }

    /** 아이디 찾기도 같은 길이다 — 아이디는 문자로만 가고 응답에는 없다. */
    @Test
    void 아이디_찾기는_아이디를_문자로만_보낸다() throws Exception {
        createAccount(Role.PARENT);
        recover("login_id", null).andExpect(status().isOk());

        String body = recover("login_id", issuedCode()).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();

        assertThat(smsSender.sent()).hasSize(2);
        assertThat(smsSender.sent().get(1).text()).contains(loginId);
        assertThat(body).doesNotContain(loginId);
    }

    /** 미등록 번호는 404 — 문자도 코드 행도 만들지 않는다. */
    @Test
    void 미등록_번호는_404이고_아무것도_남기지_않는다() throws Exception {
        recover("password", null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_NOT_FOUND"));

        assertThat(smsSender.sent()).isEmpty();
        assertThat(codeRows()).isZero();
    }

    /** 관계자 계정은 문자 복구 대상이 아니다 — 메인 관리자 경로(§6.7)로만 초기화한다(Ruling 513). */
    @Test
    void 관계자_계정은_문자_복구_대상이_아니다() throws Exception {
        createAccount(Role.STAFF);

        recover("password", null).andExpect(status().isNotFound());

        assertThat(smsSender.sent()).isEmpty();
    }

    private void createAccount(Role role) {
        accountRepository.save(Account.forSignup(academyId, loginId, passwordEncoder.encode(OLD_PASSWORD), "복구시험",
                phone, null, role));
    }

    private ResultActions recover(String type, String verificationCode) throws Exception {
        String codePart = verificationCode == null ? "" : ", \"verification_code\": \"%s\"".formatted(verificationCode);
        return mockMvc.perform(post("/api/v1/auth/recover").contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\": \"%s\", \"phone\": \"%s\"%s}".formatted(type, phone, codePart)));
    }

    private ResultActions login(String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").header("X-Client-Type", "app")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"login_id\": \"%s\", \"password\": \"%s\"}".formatted(loginId, password)));
    }

    private String issuedCode() {
        return jdbcTemplate.queryForObject(
                "SELECT code FROM verification_code WHERE phone = ? ORDER BY id DESC LIMIT 1", String.class, phone);
    }

    private int codeRows() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM verification_code WHERE phone = ?", Integer.class,
                phone);
    }

    /** 2시간 전에 발급된(60초 규칙에는 걸리지 않는) 코드 행을 미리 쌓는다. */
    private void insertOldCodes(int count) {
        for (int i = 0; i < count; i++) {
            jdbcTemplate.update("INSERT INTO verification_code (phone, code, purpose, expires_at, created_at) "
                    + "VALUES (?, '111111', 'password', now() - interval '1 hour', now() - interval '2 hours')", phone);
        }
    }

    /** 문자 발송기 자리의 가짜 — 보낸 내용을 모아 둔다(업체 없이 활성 경로를 시험한다). */
    static class RecordingSmsSender implements SmsSender {

        record Sent(String phone, String text) {
        }

        private final List<Sent> sent = new CopyOnWriteArrayList<>();

        @Override
        public void send(String phone, String text) {
            sent.add(new Sent(phone, text));
        }

        List<Sent> sent() {
            return sent;
        }

        void clear() {
            sent.clear();
        }
    }

    @TestConfiguration
    static class RecordingSmsConfig {

        @Bean
        RecordingSmsSender recordingSmsSender() {
            return new RecordingSmsSender();
        }
    }
}
