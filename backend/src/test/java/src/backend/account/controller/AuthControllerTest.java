package src.backend.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;

/**
 * §2.5~§2.9 로그인 · 토큰 재발급 · 로그아웃 · 비밀번호 변경 · 아이디/비밀번호 복구 —
 * AUTH-04·05·07·08·09, C-11(로그인 실패 누적·차단) · C-14(refresh 회전).
 *
 * <p>클라이언트 종류 판정은 두 갈래(브리프 §3.2)라 각각 별도로 검증한다 — 로그인은
 * {@code X-Client-Type} 헤더, refresh·로그아웃·비밀번호 변경은 쿠키 우선·본문 차선.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthControllerTest {

    private static final String RAW_PASSWORD = "password1234!";
    private static final String COOKIE_NAME = "refresh_token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    private Long createAccount(String academyCode, String loginId, String phone) {
        Academy academy = academyRepository.save(Academy.register(academyCode, "학원" + academyCode, "서울", null, null));
        Account account = accountRepository.save(Account.forSignup(academy.getId(), loginId,
                passwordEncoder.encode(RAW_PASSWORD), "인증테스트", phone, null, Role.PARENT));
        return account.getId();
    }

    /**
     * {@code jdbcTemplate} 로 raw UPDATE 를 친 뒤 반드시 이 메서드로 영속성 컨텍스트를 비운다 —
     * {@code createAccount} 가 만든 {@code Account} 가 이미 1차 캐시에 올라가 있어, 비우지 않으면
     * 이후 {@code accountRepository} 조회가 raw UPDATE 이전의 캐시된 자바 객체를 그대로 돌려준다
     * (Hibernate identity map, PK 로 이미 관리 중인 엔티티는 쿼리 결과로 덮어쓰지 않는다).
     */
    private void forceStatus(Long accountId, String status, int failedAttempts) {
        jdbcTemplate.update("UPDATE account SET status = ?, failed_attempts = ? WHERE id = ?",
                status, failedAttempts, accountId);
        entityManager.clear();
    }

    /** {@code /auth/password} 처럼 {@code @AllowedWhenPending} 이 없는 엔드포인트를 테스트하려면
     * {@code forSignup} 이 만든 기본 {@code pending} 상태로는 게이트에서 403 으로 막힌다 — active 로 전이해 둔다. */
    private void activateAccount(Long accountId) {
        forceStatus(accountId, "active", 0);
    }

    private String loginBody(String loginId, String password) {
        return "{\"login_id\": \"%s\", \"password\": \"%s\"}".formatted(loginId, password);
    }

    private MvcResult login(String loginId, String password, String clientType) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-Client-Type", clientType)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(loginId, password)))
                .andReturn();
    }

    private String readField(MvcResult result, String jsonPath) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), jsonPath);
    }

    private String extractCookieValue(MvcResult result) {
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).as("Set-Cookie 헤더가 있어야 한다").isNotNull();
        String firstSegment = setCookie.split(";", 2)[0];
        return firstSegment.substring(firstSegment.indexOf('=') + 1);
    }

    // ── §2.5 로그인 — 실패 누적·차단(C-11) ──────────────────────────────────

    /** 목표 문장 — blocked 계정 로그인은 403 AUTH_ACCOUNT_BLOCKED 이다(401 이면 안 됨). */
    @Test
    void blocked_계정_로그인은_403_AUTH_ACCOUNT_BLOCKED_이다() throws Exception {
        Long accountId = createAccount("P2T4AUT01", "p2t4blockedqqq", "010-7000-0001");
        forceStatus(accountId, "blocked", 5);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("p2t4blockedqqq", RAW_PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AUTH_ACCOUNT_BLOCKED"));
    }

    /** 목표 문장 — 로그인 실패가 상한(C-11=5)에 도달하면 계정이 blocked 로 전이한다. */
    @Test
    void 로그인_실패가_상한에_도달하면_계정이_blocked_로_전이한다() throws Exception {
        Long accountId = createAccount("P2T4AUT02", "p2t4failcapqqq", "010-7000-0002");

        for (int i = 0; i < 4; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody("p2t4failcapqqq", "wrong-password")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
        }
        // 5번째 실패 — 이 시점부터 401 이 아니라 403 AUTH_ACCOUNT_BLOCKED 로 바뀐다.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("p2t4failcapqqq", "wrong-password")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AUTH_ACCOUNT_BLOCKED"));

        assertThat(accountRepository.findById(accountId).orElseThrow().getStatus()).isEqualTo(AccountStatus.BLOCKED);
    }

    /** 목표 문장 — 실패 몇 회 후 성공하면 failed_attempts 가 0 이 된다(없으면 "누적만" 구현이 통과). */
    @Test
    void 실패_몇_회_후_성공하면_failed_attempts_가_0_이_된다() throws Exception {
        Long accountId = createAccount("P2T4AUT03", "p2t4resetqqqqq", "010-7000-0003");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("p2t4resetqqqqq", "wrong-password")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("p2t4resetqqqqq", "wrong-password")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("p2t4resetqqqqq", RAW_PASSWORD)))
                .andExpect(status().isOk());

        assertThat(accountRepository.findById(accountId).orElseThrow().getFailedAttempts()).isZero();
    }

    // ── §2.5 로그인 — 클라이언트별 refresh 전달(브리프 §3) ──────────────────

    /** 목표 문장 — web 로그인 응답 본문에 refresh_token 키가 부재한다. */
    @Test
    void web_로그인_응답_본문에_refresh_token_키가_부재한다() throws Exception {
        createAccount("P2T4AUT04", "p2t4webbodyqqq", "010-7000-0004");

        mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-Client-Type", "web")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("p2t4webbodyqqq", RAW_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.refresh_token").doesNotExist())
                .andExpect(jsonPath("$.data.access_token").exists());
    }

    /** 목표 문장 — web 로그인 응답의 Set-Cookie 에 HttpOnly·Secure·SameSite=Strict·Path 가 각각 존재한다(4개 개별 단언). */
    @Test
    void web_로그인_응답의_Set_Cookie_에_HttpOnly_Secure_SameSite_Strict_Path_가_각각_존재한다() throws Exception {
        createAccount("P2T4AUT05", "p2t4webcookqqq", "010-7000-0005");

        mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-Client-Type", "web")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("p2t4webcookqqq", RAW_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Secure")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=Strict")))
                // API_SPEC §1.2.1 본문의 /api/auth 는 낡은 값 — Ruling 102 로 /api/v1/auth 가 맞다(보고서 ⑤).
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Path=/api/v1/auth")));
    }

    // ── §2.6 refresh ─────────────────────────────────────────────────────

    /** 목표 문장 — 쿠키만 담은 refresh 요청이 access 를 재발급한다. */
    @Test
    void 쿠키만_담은_refresh_요청이_access_를_재발급한다() throws Exception {
        createAccount("P2T4AUT06", "p2t4refcookqqq", "010-7000-0006");
        MvcResult loginResult = login("p2t4refcookqqq", RAW_PASSWORD, "app");
        String refreshToken = readField(loginResult, "$.data.refresh_token");
        String oldAccessToken = readField(loginResult, "$.data.access_token");

        mockMvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(COOKIE_NAME, refreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.access_token").exists())
                .andExpect(jsonPath("$.data.access_token").value(org.hamcrest.Matchers.not(oldAccessToken)))
                // 쿠키로 들어온 요청이므로 웹으로 간주 — 응답 본문에도 refresh_token 이 없어야 한다(§3.2).
                .andExpect(jsonPath("$.data.refresh_token").doesNotExist());
    }

    /** 목표 문장 — 본문과 쿠키 어디에도 refresh 가 없으면 401 TOKEN_EXPIRED 이다(없으면 "아무나 재발급" 구현이 통과). */
    @Test
    void 본문과_쿠키_어디에도_refresh_가_없으면_401_TOKEN_EXPIRED_이다() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_EXPIRED"));
    }

    // ── §2.7 로그아웃 ────────────────────────────────────────────────────

    /** 목표 문장 — web 로그아웃 응답에 Max-Age=0 쿠키 삭제 지시가 있다. */
    @Test
    void web_로그아웃_응답에_Max_Age_0_쿠키_삭제_지시가_있다() throws Exception {
        createAccount("P2T4AUT07", "p2t4logoutwbqq", "010-7000-0007");
        MvcResult loginResult = login("p2t4logoutwbqq", RAW_PASSWORD, "web");
        String accessToken = readField(loginResult, "$.data.access_token");
        String refreshTokenCookieValue = extractCookieValue(loginResult);

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + accessToken)
                        .cookie(new Cookie(COOKIE_NAME, refreshTokenCookieValue)))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
    }

    /** 목표 문장 — pending 계정도 로그아웃할 수 있다(§1.4 허용 5개 중 하나). */
    @Test
    void pending_계정도_로그아웃할_수_있다() throws Exception {
        createAccount("P2T4AUT08", "p2t4logoutpdqq", "010-7000-0008");
        MvcResult loginResult = login("p2t4logoutpdqq", RAW_PASSWORD, "app");
        String accessToken = readField(loginResult, "$.data.access_token");
        String refreshToken = readField(loginResult, "$.data.refresh_token");
        assertThat(readField(loginResult, "$.data.status")).isEqualTo("pending");

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refresh_token\": \"%s\"}".formatted(refreshToken)))
                .andExpect(status().isNoContent());
    }

    // ── §2.8 비밀번호 변경 ───────────────────────────────────────────────

    /** §2.8 성공 시 기존 refresh 토큰을 전량 무효화한다 — 회전 전 토큰으로 재발급이 더는 안 된다. */
    @Test
    void 비밀번호_변경_성공_시_기존_refresh_토큰이_전량_무효화된다() throws Exception {
        Long accountId = createAccount("P2T4AUT09", "p2t4pwchangeqq", "010-7000-0009");
        activateAccount(accountId); // /auth/password 는 @AllowedWhenPending 이 없어 pending 이면 게이트에서 403.
        MvcResult loginResult = login("p2t4pwchangeqq", RAW_PASSWORD, "app");
        String accessToken = readField(loginResult, "$.data.access_token");
        String refreshToken = readField(loginResult, "$.data.refresh_token");

        mockMvc.perform(post("/api/v1/auth/password")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"current_password\": \"%s\", \"new_password\": \"new-password5678!\"}"
                                .formatted(RAW_PASSWORD)))
                .andExpect(status().isNoContent());

        // 변경 전 refresh 토큰은 이제 무효 — 재발급 요청이 401 TOKEN_EXPIRED 여야 한다.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refresh_token\": \"%s\"}".formatted(refreshToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_EXPIRED"));
    }

    /** §2.8 현재 비밀번호가 틀리면 401 INVALID_CREDENTIALS — 새 비밀번호로 바뀌지 않아야 한다. */
    @Test
    void 현재_비밀번호가_틀리면_401_INVALID_CREDENTIALS_이고_변경되지_않는다() throws Exception {
        Long accountId = createAccount("P2T4AUT10", "p2t4pwwrongqqq", "010-7000-0010");
        activateAccount(accountId); // /auth/password 는 @AllowedWhenPending 이 없어 pending 이면 게이트에서 403.
        MvcResult loginResult = login("p2t4pwwrongqqq", RAW_PASSWORD, "app");
        String accessToken = readField(loginResult, "$.data.access_token");

        mockMvc.perform(post("/api/v1/auth/password")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"current_password\": \"wrong-current\", \"new_password\": \"new-password5678!\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("p2t4pwwrongqqq", RAW_PASSWORD)))
                .andExpect(status().isOk());
    }

    // ── §2.9 아이디·비밀번호 복구 ────────────────────────────────────────

    /** §2.9 verification_code 미전달 = 코드 발송 요청 — 코드가 DB 에 남는다. */
    @Test
    void 코드_미포함_복구_요청은_코드를_발송하고_저장한다() throws Exception {
        createAccount("P2T4AUT11", "p2t4recoversnd", "010-7000-0011");

        mockMvc.perform(post("/api/v1/auth/recover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\": \"login_id\", \"phone\": \"010-7000-0011\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code_sent").value(true));

        String savedCode = jdbcTemplate.queryForObject(
                "SELECT code FROM verification_code WHERE phone = ? ORDER BY created_at DESC LIMIT 1",
                String.class, "010-7000-0011");
        assertThat(savedCode).hasSize(6);
    }

    /** §2.9 아이디 복구 — 코드 대조 성공 시 등록된 로그인 아이디를 돌려준다. */
    @Test
    void 아이디_복구는_코드_대조_후_로그인_아이디를_반환한다() throws Exception {
        createAccount("P2T4AUT12", "p2t4recoverid", "010-7000-0012");
        mockMvc.perform(post("/api/v1/auth/recover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\": \"login_id\", \"phone\": \"010-7000-0012\"}"))
                .andExpect(status().isOk());
        String code = jdbcTemplate.queryForObject(
                "SELECT code FROM verification_code WHERE phone = ? ORDER BY created_at DESC LIMIT 1",
                String.class, "010-7000-0012");

        mockMvc.perform(post("/api/v1/auth/recover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\": \"login_id\", \"phone\": \"010-7000-0012\", \"verification_code\": \"%s\"}"
                                .formatted(code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.login_id").value("p2t4recoverid"));
    }

    /** §2.9 비밀번호 복구 — 코드 대조 성공 시 임시 비밀번호를 발급하고, 그 비밀번호로 즉시 로그인할 수 있다. */
    @Test
    void 비밀번호_복구는_코드_대조_후_임시_비밀번호로_로그인할_수_있게_한다() throws Exception {
        createAccount("P2T4AUT13", "p2t4recoverpw", "010-7000-0013");
        mockMvc.perform(post("/api/v1/auth/recover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\": \"password\", \"phone\": \"010-7000-0013\"}"))
                .andExpect(status().isOk());
        String code = jdbcTemplate.queryForObject(
                "SELECT code FROM verification_code WHERE phone = ? ORDER BY created_at DESC LIMIT 1",
                String.class, "010-7000-0013");

        MvcResult recoverResult = mockMvc.perform(post("/api/v1/auth/recover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\": \"password\", \"phone\": \"010-7000-0013\", \"verification_code\": \"%s\"}"
                                .formatted(code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.temporary_password").exists())
                .andReturn();
        String temporaryPassword = readField(recoverResult, "$.data.temporary_password");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("p2t4recoverpw", temporaryPassword)))
                .andExpect(status().isOk());
    }
}
