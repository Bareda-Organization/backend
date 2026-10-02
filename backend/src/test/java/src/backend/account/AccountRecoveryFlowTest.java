package src.backend.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
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
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.ResultActions;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.account.command.AccountRecoveryCommandService;
import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.global.common.enums.Role;
import src.backend.global.sms.impl.LoggingSmsSender;
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

    @Autowired
    private AccountRecoveryCommandService accountRecoveryCommandService;

    private String phone;

    private String loginId;

    private Long academyId;

    private final Logger smsLogger = (Logger) LoggerFactory.getLogger(LoggingSmsSender.class);

    private Level smsLoggerLevelBefore;

    /**
     * 문자 발송기의 INFO 로그를 켠다 — CI 의 {@code -PciQuiet} 은 루트 수준을 WARN 으로 낮춰, 안 켜면 로그에 비밀이 없다는 검사가 빈 로그를 보고
     * 통과한다(공허 통과 · R46-CIFIX 가 {@code LoggingSmsSender} 시험에 한 것과 같은 방식).
     */
    @BeforeEach
    void showSmsLog() {
        smsLoggerLevelBefore = smsLogger.getLevel();
        smsLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void restoreSmsLogLevel() {
        smsLogger.setLevel(smsLoggerLevelBefore);
    }

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
        assertThat(output.getAll()).as("검사 대상 로그 — 비어 있으면 아래 '없음' 검사가 아무것도 안 본다").contains("[sms] to=***");
        assertThat(output.getAll()).doesNotContain(code).doesNotContain(tempPassword).doesNotContain(phone);
    }

    /** 문자로 받은 임시 비밀번호에도 강제 변경 표식이 선다(Ruling 785) — 문자함에 평문으로 남는 값을 그대로 쓰게 두지 않는다. */
    @Test
    void 문자_복구로_받은_임시_비밀번호는_강제_변경_표식을_세운다() throws Exception {
        createAccount(Role.PARENT);
        assertThat(mustChangePassword()).as("복구 전").isFalse();
        recover("password", null).andExpect(status().isOk());

        recover("password", issuedCode()).andExpect(status().isOk());

        assertThat(mustChangePassword()).as("복구 뒤").isTrue();
        String tempPassword = lastTemporaryPassword();
        login(tempPassword).andExpect(status().isOk()).andExpect(jsonPath("$.data.must_change_password").value(true));
    }

    /**
     * 코드 발급은 문자를 보내는 동안 계정 행을 잠그지 않는다(BR-308) — 번호 직렬화는 advisory lock 이 맡고 발급은 계정을
     * 읽기만 한다. 행을 쥐고 있으면 전화번호만 아는 요청이 남의 로그인을 문자 업체 응답 시간만큼 세운다. 발송 도중 다른
     * 연결에서 그 행을 {@code FOR UPDATE NOWAIT} 로 잡아 본다.
     */
    @Test
    void 코드_발급은_문자를_보내는_동안_계정_행을_잠그지_않는다() throws Exception {
        createAccount(Role.PARENT);
        Long accountId = jdbcTemplate.queryForObject("SELECT id FROM account WHERE login_id = ?", Long.class, loginId);
        ExecutorService otherConnection = Executors.newSingleThreadExecutor();
        AtomicReference<Throwable> lockFailure = new AtomicReference<>();
        AtomicReference<Boolean> probed = new AtomicReference<>(false);
        smsSender.duringSend(() -> {
            probed.set(true);
            try {
                otherConnection.submit(() -> jdbcTemplate
                        .queryForList("SELECT id FROM account WHERE id = ? FOR UPDATE NOWAIT", accountId)).get(10,
                                TimeUnit.SECONDS);
            } catch (Exception e) {
                lockFailure.set(e);
            }
        });
        try {
            recover("password", null).andExpect(status().isOk());
        } finally {
            otherConnection.shutdownNow();
        }

        assertThat(probed.get()).as("발송 도중 확인이 실행됐다").isTrue();
        assertThat(lockFailure.get()).as("발송 도중 다른 연결이 계정 행을 잠글 수 있다").isNull();
    }

    /**
     * 차단된 계정은 문자 복구 대상이 아니다(BR-336) — 퇴원 90일 파기로 익명화된 학생 계정이 모두 같은 가짜 번호를 갖고
     * {@code blocked} 라서, 대상에 두면 그 번호로 파기 계정 전부가 잠기고 문자가 나간다. 차단 계정은 복구해도 로그인이
     * {@code 403} 이라 빼도 잃는 것이 없다. 발급은 응답이 같고(Ruling 553) 문자만 안 나가며, 대조도 실패한다.
     */
    @Test
    void 차단된_계정은_문자_복구_대상이_아니다() throws Exception {
        createAccount(Role.PARENT);
        jdbcTemplate.update("UPDATE account SET status = 'blocked', status_before_block = 'active', "
                + "blocked_at = now(), block_reason = '시험' WHERE login_id = ?", loginId);

        recover("password", null).andExpect(status().isOk());
        assertThat(smsSender.sent()).as("발급 문자").isEmpty();
        recover("password", issuedCode()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("VERIFICATION_CODE_INVALID"));

        assertThat(smsSender.sent()).as("임시 비밀번호 문자").isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT password_hash FROM account WHERE login_id = ?", String.class,
                loginId)).as("비밀번호는 그대로").satisfies(hash -> assertThat(passwordEncoder.matches(OLD_PASSWORD, hash)).isTrue());
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

    /**
     * 번호가 가입돼 있는지 응답으로 알 수 없어야 한다(R46 FUBE · Ruling 553) — 미등록 번호도 발급 요청에 같은 {@code 200} 이고
     * 응답 본문이 같다. 문자는 가입된 번호에만 나간다.
     */
    @Test
    void 미등록_번호의_발급_요청도_등록_번호와_같은_응답이고_문자는_보내지_않는다() throws Exception {
        String registeredBody = recover("password", null).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        // 위는 계정이 아직 없던 번호 — 이어서 같은 번호에 계정을 만들고 다른 번호로 같은 요청을 보내 비교한다
        smsSender.clear();
        String unregisteredPhone = phone;
        phone = "010-" + ThreadLocalRandom.current().nextInt(10_000_000, 100_000_000);
        loginId = "r46recb" + phone.substring(4);
        createAccount(Role.PARENT);

        String body = recover("password", null).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();

        assertThat(body).as("가입된 번호의 응답 본문").isEqualTo(registeredBody);
        assertThat(smsSender.sent()).as("문자는 가입된 번호에만").hasSize(1);
        assertThat(smsSender.sent().get(0).phone()).isEqualTo(phone);
        jdbcTemplate.update("DELETE FROM verification_code WHERE phone = ?", unregisteredPhone);
    }

    /** 빈도 제한도 번호 기준으로 같다 — 미등록 번호만 제한이 안 걸리면 그 차이로 가입 여부가 드러난다. */
    @Test
    void 미등록_번호도_60초_안에_다시_발급하면_같은_429_이다() throws Exception {
        recover("password", null).andExpect(status().isOk());

        recover("password", null).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RECOVERY_RATE_LIMITED"));

        assertThat(smsSender.sent()).isEmpty();
    }

    @Test
    void 미등록_번호도_하루_5회를_넘기면_같은_429_이다() throws Exception {
        insertOldCodes(5);

        recover("password", null).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RECOVERY_RATE_LIMITED"));
    }

    /** 같은 번호로 동시에 들어온 발급은 한 건만 통과한다 — 등록 여부와 무관하게(미등록만 둘 다 통과하면 그 차이가 단서다). */
    @Test
    void 미등록_번호로_동시에_발급해도_한_건만_통과한다() throws Exception {
        int requests = 6;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return recover("password", null).andReturn().getResponse().getStatus();
                }));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(30, TimeUnit.SECONDS));
            }

            assertThat(statuses).filteredOn(code -> code == 200).hasSize(1);
            assertThat(statuses).filteredOn(code -> code == 429).hasSize(requests - 1);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 미등록 번호의 요청도 발급 행을 남기므로 하루가 지난 행은 정리한다 — 번호를 바꿔 가며 보내는 요청이 행을 끝없이 쌓지 못하게.
     * 하루 안의 행은 남아야 한다(그 행이 24시간 한도를 센다).
     */
    @Test
    void 하루가_지난_발급_행은_정리되고_하루_안의_행은_남는다() throws Exception {
        jdbcTemplate.update("INSERT INTO verification_code (phone, code, purpose, expires_at, created_at) "
                + "VALUES (?, '111111', 'password', now() - interval '47 hours', now() - interval '2 days')", phone);
        jdbcTemplate.update("INSERT INTO verification_code (phone, code, purpose, expires_at, created_at) "
                + "VALUES (?, '222222', 'password', now() - interval '1 hour', now() - interval '2 hours')", phone);
        // 정리는 10분에 한 번만 도므로, 앞선 시험이 이미 돌았더라도 이 시험에서 돌게 마지막 정리 시각을 되돌린다
        @SuppressWarnings("unchecked")
        AtomicReference<Instant> lastPurgeAt = (AtomicReference<Instant>) ReflectionTestUtils
                .getField(accountRecoveryCommandService, "lastPurgeAt");
        lastPurgeAt.set(Instant.EPOCH);

        recover("password", null).andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForList("SELECT code FROM verification_code WHERE phone = ? ORDER BY id",
                String.class, phone)).as("2일 전 행은 지워지고 2시간 전 행과 방금 발급한 행이 남는다")
                .hasSize(2).contains("222222").doesNotContain("111111");
    }

    /** 대조 실패도 같은 오류다 — 미등록 번호도, 발급 행이 있는 미등록 번호가 맞는 값을 넣어도 {@code 403} 하나다. */
    @Test
    void 미등록_번호의_대조_실패는_틀린_코드와_같은_403_이다() throws Exception {
        String neverIssued = recover("password", "123456").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("VERIFICATION_CODE_INVALID")).andReturn().getResponse()
                .getContentAsString();
        recover("password", null).andExpect(status().isOk());

        String withStoredCode = recover("password", issuedCode()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("VERIFICATION_CODE_INVALID")).andReturn().getResponse()
                .getContentAsString();

        assertThat(withStoredCode).isEqualTo(neverIssued);
        assertThat(smsSender.sent()).isEmpty();
    }

    /** 관계자 계정은 문자 복구 대상이 아니다 — 메인 관리자 경로(§6.7)로만 초기화한다(Ruling 513). 응답은 미등록과 같다. */
    @Test
    void 관계자_계정은_문자_복구_대상이_아니고_응답은_미등록과_같다() throws Exception {
        createAccount(Role.STAFF);

        recover("password", null).andExpect(status().isOk());

        assertThat(smsSender.sent()).isEmpty();
        login(OLD_PASSWORD).andExpect(status().isOk());
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

    private boolean mustChangePassword() {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT must_change_password FROM account WHERE login_id = ?", Boolean.class, loginId));
    }

    /** 가장 최근 문자 본문에서 임시 비밀번호를 꺼낸다. */
    private String lastTemporaryPassword() {
        Matcher temp = TEMP_PASSWORD.matcher(smsSender.sent().get(smsSender.sent().size() - 1).text());
        assertThat(temp.find()).as("문자 본문에 임시 비밀번호").isTrue();
        return temp.group(1);
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

    /**
     * 문자 발송기 자리의 가짜 — 보낸 내용을 모아 둔다(업체 없이 활성 경로를 시험한다). 실제 로그 전용 발송기({@link LoggingSmsSender})에도
     * 같이 넘긴다 — 이 흐름에서 코드·임시 비밀번호·번호가 지나는 로그 줄은 그 발송기의 것뿐이라, 로그에 비밀이 없다는 검사가 볼 대상이 생긴다.
     */
    static class RecordingSmsSender implements SmsSender {

        record Sent(String phone, String text) {
        }

        private final List<Sent> sent = new CopyOnWriteArrayList<>();

        private final SmsSender logging = new LoggingSmsSender(new MockEnvironment());

        /** 발송하는 순간 실행할 확인 — 발송이 잠금·트랜잭션 안에서 일어나는지 본다(기본은 없음). */
        private volatile Runnable duringSend = () -> {
        };

        @Override
        public void send(String phone, String text) {
            duringSend.run();
            sent.add(new Sent(phone, text));
            logging.send(phone, text);
        }

        void duringSend(Runnable check) {
            duringSend = check;
        }

        List<Sent> sent() {
            return sent;
        }

        void clear() {
            sent.clear();
            duringSend = () -> {
            };
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
