package src.backend.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Propagation;
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
 *
 * <p><b>{@code reference.md §20.2} 의 200줄을 넘긴 채 두는 이유</b>(§19 요구) — 다섯 절이 픽스처 헬퍼
 * 14개를 공유하고, 그중 {@code createAccount} · {@code login} · {@code readField} · {@code readObject} ·
 * {@code loginBody} 5개는 절을 가리지 않고 쓰인다. 절 단위로 나누면 이 5개를 상위 클래스로 올리거나
 * 복제해야 하는데, 상위 클래스는 {@code @SpringBootTest} 픽스처를 상속으로 잇는 형태라 어느 하위가
 * 무엇을 쓰는지 읽어서 알 수 없게 되고, 복제는 §2.5 계약이 바뀔 때 한쪽만 고쳐질 자리를 만든다.
 * 절 사이 결합도 실재한다 — §2.8·§2.9 단언이 "바뀐 비밀번호로 §2.5 로그인이 되는가" 로 끝난다.
 * 헬퍼 공유가 사라지는 시점(§2.9 가 자체 픽스처만 쓰게 되는 등)이 오면 그때 분리한다.
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

    /**
     * 쿠키 {@code Max-Age} 단언이 쓰는 값 — 테스트에 숫자를 박으면 설정만 바뀌었을 때 테스트가 먼저
     * 깨지는 대신 조용히 옛 값을 요구한다. 설정과 같은 자리에서 읽어 둘이 함께 움직이게 한다.
     */
    @Value("${jwt.refresh-token-validity-seconds}")
    private long refreshTokenValiditySeconds;

    @PersistenceContext
    private EntityManager entityManager;

    private Long createAccount(String academyCode, String loginId, String phone) {
        Academy academy = academyRepository.save(Academy.register(academyCode, "학원" + academyCode, "서울", null, null));
        Account account = accountRepository.save(Account.forSignup(academy.getId(), loginId,
                passwordEncoder.encode(RAW_PASSWORD), "인증테스트", phone, null, Role.PARENT));
        return account.getId();
    }

    /**
     * {@code system_admin} 계정을 만든다. {@code academyId} 에 {@code null} 과 실제 학원 둘 다 넘길 수
     * 있게 열어 둔 이유는, {@code ck_account_academy_scope} 가
     * {@code role = 'system_admin' OR academy_id IS NOT NULL} 이라 <b>소속을 가진 system_admin 도
     * 스키마가 허용</b>하기 때문이다. §2.5 의 "{@code system_admin} 은 academy 가 null" 이 소속 유무가
     * 아니라 역할로 판정될 때만 참인데, {@code null} 만 넘기면 두 판정이 갈리는 입력이 픽스처에
     * 부재해 그 차이가 검증되지 않는다(리뷰 라운드 2 m-1).
     */
    private void createSystemAdminAccount(Long academyId, String loginId, String phone) {
        accountRepository.save(Account.forSignup(academyId, loginId, passwordEncoder.encode(RAW_PASSWORD),
                "플랫폼관리자", phone, null, Role.SYSTEM_ADMIN));
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

    /**
     * JSON 객체 하나를 {@link Map} 으로 읽는다 — {@code jsonPath()} 매처로는 "키가 빠짐" 과 "키가 있고
     * 값이 null" 을 가르지 못하기 때문이다({@code exists()} 는 값이 null 이면 실패하고
     * {@code doesNotExist()} 는 값이 null 이어도 통과한다). {@code Map.containsKey} 만이 둘을 가른다.
     */
    private Map<String, Object> readObject(MvcResult result, String jsonPath) throws Exception {
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

    /**
     * 목표 문장 — 로그인 실패가 상한(C-11=5)에 도달하면 계정이 blocked 로 전이한다.
     *
     * <p>회차마다 {@code details.remaining_attempts} 를 <b>값까지</b> 본다(4·3·2·1) — 존재만 보면
     * "항상 0" 구현이 통과한다. 이 필드는 API_SPEC 이 이 문서에서 처음 정한 신규 결정이라 선례가
     * 없고, {@code ErrorResponse.details} 의 타입이 {@code Object} 라 전역 snake_case 전략이 그
     * 하위까지 내려가는지도 이 단언이 유일한 확인 수단이다({@code remainingAttempts} 로 나가면 여기서
     * 걸린다).
     */
    @Test
    void 로그인_실패가_상한에_도달하면_계정이_blocked_로_전이한다() throws Exception {
        Long accountId = createAccount("P2T4AUT02", "p2t4failcapqqq", "010-7000-0002");

        // 회차 수와 기대값을 C-11 상한에서 끌어 쓴다 — 4·5 를 박아 두면 상한을 바꿨을 때 옛 값을 요구한다.
        for (int i = 0; i < Account.MAX_FAILED_ATTEMPTS - 1; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody("p2t4failcapqqq", "wrong-password")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
                    .andExpect(jsonPath("$.error.details.remaining_attempts")
                            .value(Account.MAX_FAILED_ATTEMPTS - 1 - i));
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

    /**
     * 목표 문장 — 미등록 login_id 의 실패 응답이 존재하는 계정의 첫 실패와 형태·값까지 같다(계정 열거 차단).
     *
     * <p>상태 코드와 에러 코드만 맞추고 {@code details} 유무가 갈리면, 공격자는 본문 모양만 보고
     * "이 아이디는 존재한다" 를 알아낸다. 두 응답을 같은 테스트에서 나란히 읽어 대조한다 — 나눠
     * 쓰면 한쪽만 바뀌었을 때 둘 다 통과한다.
     *
     * <p><b>키 집합만 견주면 부족하다</b> — 한쪽이 5, 한쪽이 4 여도 키는 같아서 통과하는데 그 숫자
     * 하나가 곧 계정 존재 신호다(리뷰 라운드 2 I-1). 그래서 {@code details} 를 <b>값까지</b> 통째로
     * 대조하고, 그 값이 {@link Account#REMAINING_AFTER_FIRST_FAILURE} 인지도 함께 못박는다.
     *
     * <p>이 단언이 막는 것은 1회 프로브뿐이며 남는 한계는 {@code LoginCommandService} javadoc 에 적었다.
     */
    @Test
    void 미등록_login_id_의_실패_응답이_존재하는_계정의_첫_실패와_같다() throws Exception {
        createAccount("P2T4AUT16", "p2t4enumexists", "010-7000-0016");

        MvcResult unknown = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("p2t4enumabsent", "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
                .andReturn();

        MvcResult known = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("p2t4enumexists", "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
                .andReturn();

        assertThat(readObject(unknown, "$.error").keySet())
                .as("에러 본문의 키 집합이 갈리면 그 차이만으로 계정 존재를 가려낼 수 있다")
                .isEqualTo(readObject(known, "$.error").keySet());
        assertThat(readObject(unknown, "$.error.details"))
                .as("details 는 키 집합이 아니라 값까지 같아야 한다 — 5 인지 4 인지가 곧 계정 존재 신호다")
                .isEqualTo(readObject(known, "$.error.details"));
        assertThat(readObject(known, "$.error.details").get("remaining_attempts"))
                .as("존재 계정의 첫 실패는 상한에서 1 을 뺀 값이고, 미등록도 같은 상수를 싣는다")
                .isEqualTo(Account.REMAINING_AFTER_FIRST_FAILURE);
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
                // 베이스 경로 접두사를 포함해야 /api/v1/auth/refresh 에 실제로 동봉된다(API_SPEC §1.2.1).
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Path=/api/v1/auth")))
                // Max-Age 가 빠지면 세션 쿠키가 돼 브라우저를 닫는 순간 자동 로그인이 소멸한다 —
                // 로컬에서는 브라우저를 안 닫으니 재현되지 않고, 값이 -1 이어도 나머지 4속성은 그대로다.
                .andExpect(header().string(HttpHeaders.SET_COOKIE,
                        containsString("Max-Age=" + refreshTokenValiditySeconds)));
    }

    /**
     * 목표 문장 — system_admin 로그인 응답의 academy 는 키가 빠지는 것이 아니라 null 로 있다(§2.5).
     *
     * <p>단언에 {@code jsonPath} 매처를 쓰지 않는 이유는 {@link #readObject} 주석에 적었다.
     */
    @Test
    void system_admin_로그인_응답의_academy_는_키가_있고_값이_null_이다() throws Exception {
        createSystemAdminAccount(null, "p2t4sysadminqq", "010-7000-0015");

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("p2t4sysadminqq", RAW_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("system_admin"))
                .andReturn();

        Map<String, Object> data = readObject(result, "$.data");
        assertThat(data).as("academy 키 자체가 빠지면 안 된다 — §2.5 는 null 값으로 규정").containsKey("academy");
        assertThat(data.get("academy")).as("system_admin 의 academy 는 null 이다").isNull();
    }

    /**
     * 목표 문장 — academy_id 를 보유한 system_admin 의 로그인 응답도 academy 가 null 이다(§2.5).
     *
     * <p>{@code AuthController} 가 소속 유무({@code academyId == null})가 아니라 <b>역할</b>로 판정해야만
     * 참인 문장이다 — 위 테스트는 두 판정이 같은 답을 내는 입력만 쓰므로 판정을 소속 유무로 되돌려도
     * 통과한다(리뷰 라운드 2 m-1). 이 조합은 {@code ck_account_academy_scope} 가 허용하므로 실재 가능한
     * 입력이고, 소속 유무로 판정하면 이 계정에만 {@code academy} 객체가 실려 §2.5 를 어긴다.
     */
    @Test
    void academy_id_를_보유한_system_admin_의_로그인_응답도_academy_가_null_이다() throws Exception {
        Academy academy = academyRepository.save(Academy.register("P2T4AUT21", "학원P2T4AUT21", "서울", null, null));
        createSystemAdminAccount(academy.getId(), "p2t4sysadminaf", "010-7000-0021");

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("p2t4sysadminaf", RAW_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("system_admin"))
                .andReturn();

        Map<String, Object> data = readObject(result, "$.data");
        assertThat(data).as("academy 키 자체가 빠지면 안 된다 — §2.5 는 null 값으로 규정").containsKey("academy");
        assertThat(data.get("academy"))
                .as("소속이 있어도 system_admin 이면 academy 는 null 이다 — 소속 유무가 아니라 역할로 판정")
                .isNull();
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

    /**
     * 목표 문장 — 단말 A 로그아웃은 A 의 refresh 만 무효화하고 단말 B 는 계속 재발급받는다(Ruling 99).
     *
     * <p>두 단언을 <b>한 테스트에 함께</b> 둔다. 나누면 무효화 범위가 잘못돼도 한쪽은 통과한다 —
     * 로그아웃이 아무것도 무효화하지 않으면 "A 가 401" 만 깨지고, 계정 전량을 무효화하면 "B 가 200"
     * 만 깨진다. 지난 라운드에는 둘 다 부재해, 로그아웃 본체를 통째로 지워도 14건이 전건 통과했다.
     *
     * <p>말미에 §2.6 회전 검증을 얹는다 — B 가 재발급을 받으면 B 의 <b>옛</b> 토큰은 그 자리에서
     * 무효화돼야 한다. 살아 있으면 한 번 탈취된 refresh 토큰이 영구 유효해진다.
     */
    @Test
    void 단말_A_로그아웃은_A_토큰만_무효화하고_단말_B_는_유지한다() throws Exception {
        createAccount("P2T4AUT17", "p2t4twodevice", "010-7000-0017");
        MvcResult deviceALogin = login("p2t4twodevice", RAW_PASSWORD, "app");
        String deviceARefresh = readField(deviceALogin, "$.data.refresh_token");
        String deviceAAccess = readField(deviceALogin, "$.data.access_token");
        String deviceBRefresh = readField(login("p2t4twodevice", RAW_PASSWORD, "app"), "$.data.refresh_token");
        assertThat(deviceARefresh).as("단말별 refresh 토큰이 갈려야 이 테스트가 성립한다").isNotEqualTo(deviceBRefresh);

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + deviceAAccess)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refresh_token\": \"%s\"}".formatted(deviceARefresh)))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refresh_token\": \"%s\"}".formatted(deviceARefresh)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_EXPIRED"));

        String rotatedBRefresh = readField(mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refresh_token\": \"%s\"}".formatted(deviceBRefresh)))
                .andExpect(status().isOk())
                .andReturn(), "$.data.refresh_token");
        assertThat(rotatedBRefresh).as("§2.6 회전 — 재발급은 새 refresh 를 내려야 한다").isNotEqualTo(deviceBRefresh);

        // m4 — 회전 직후 옛 토큰 재사용은 401 이다.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refresh_token\": \"%s\"}".formatted(deviceBRefresh)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_EXPIRED"));
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

    /**
     * §2.8 성공 시 기존 refresh 토큰을 전량 무효화한다 — 회전 전 토큰으로 재발급이 더는 안 된다.
     *
     * <p>말미에 <b>새 비밀번호가 실제로 반영됐는지</b>를 로그인 두 번으로 확인한다. 이 단언이 없으면
     * 사용자가 204 를 받고도 비밀번호가 그대로인 상태를 테스트 전체 묶음이 검출하지 못한다 — 지난 라운드에
     * 실재했던 결함({@code RefreshTokenRepository} 의 {@code flushAutomatically} 누락)이 정확히 그
     * 형태였고, 토큰 무효화만 보는 단언은 그 결함을 통과시켰다.
     */
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

        // 본체 — 새 비밀번호로는 로그인되고 옛 비밀번호로는 안 된다(204 만 받고 반영이 사라진 상태를 가른다).
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("p2t4pwchangeqq", "new-password5678!")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("p2t4pwchangeqq", RAW_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
    }

    /**
     * §2.8 웹 호출은 §2.7 과 같은 쿠키 삭제 지시를 함께 돌려준다 — 전량 무효화로 죽은 쿠키가
     * 브라우저에 남으면 다음 접속이 401 을 한 번 더 거친다.
     *
     * <p>말미에 <b>app 호출에는 붙지 않는 것</b>까지 본다. "붙는다" 만 단언하면 조건을 없애고 무조건
     * 붙이는 구현이 통과하는데, §2.8 은 "<b>웹 호출이면</b>" 이라 그 조건이 계약의 일부다(리뷰 라운드
     * 2 m-2). 웹 판정은 쿠키 동봉 여부이므로 app 호출은 쿠키를 보내지 않는 것으로 표현한다.
     */
    @Test
    void web_비밀번호_변경_응답에_Max_Age_0_쿠키_삭제_지시가_있다() throws Exception {
        Long accountId = createAccount("P2T4AUT18", "p2t4pwwebqqqqq", "010-7000-0018");
        activateAccount(accountId);
        MvcResult loginResult = login("p2t4pwwebqqqqq", RAW_PASSWORD, "web");
        String accessToken = readField(loginResult, "$.data.access_token");

        mockMvc.perform(post("/api/v1/auth/password")
                        .header("Authorization", "Bearer " + accessToken)
                        .cookie(new Cookie(COOKIE_NAME, extractCookieValue(loginResult)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"current_password\": \"%s\", \"new_password\": \"new-password5678!\"}"
                                .formatted(RAW_PASSWORD)))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")))
                // 발급 시와 속성이 같아야 브라우저가 같은 쿠키로 인식해 지운다(§2.7).
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Path=/api/v1/auth")));

        // app 호출(쿠키 미동봉)에는 삭제 지시가 붙지 않는다 — 쿠키를 쓰지 않는 클라이언트에 보낼 이유가 부재.
        Long appAccountId = createAccount("P2T4AUT22", "p2t4pwappqqqqq", "010-7000-0022");
        activateAccount(appAccountId);
        String appAccessToken = readField(login("p2t4pwappqqqqq", RAW_PASSWORD, "app"), "$.data.access_token");

        mockMvc.perform(post("/api/v1/auth/password")
                        .header("Authorization", "Bearer " + appAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"current_password\": \"%s\", \"new_password\": \"new-password5678!\"}"
                                .formatted(RAW_PASSWORD)))
                .andExpect(status().isNoContent())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
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

    /**
     * 목표 문장 — 인증 코드를 5회 틀리면 그 뒤로는 <b>옳은 코드도</b> 거부된다.
     *
     * <p>이 메서드만 클래스 {@code @Transactional} 밖에서 돈다({@code NOT_SUPPORTED}). 검증 대상이
     * "실패 누적이 커밋되는가" 인데, 테스트 트랜잭션 안에서는 요청이 실패해도 실제 롤백이 일어나지
     * 않아 <b>누적을 잃는 구현과 지키는 구현이 같은 결과를 낸다</b> — 트랜잭션 안에 두면 이 단언은
     * 아무것도 검사하지 못한다. 대신 커밋된 행이 남으므로 앞뒤로 직접 지운다.
     *
     * <p>6회째에 <b>옳은</b> 코드를 넣는 것이 핵심이다. 틀린 코드를 넣으면 상한이 있든 없든 403 이라
     * 두 구현이 갈리지 않는다.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 인증_코드를_5회_틀리면_옳은_코드도_거부된다() throws Exception {
        String academyCode = "P2T4AUT19";
        String loginId = "p2t4reccapqqq";
        String phone = "010-7000-0019";
        deleteRecoverFixture(academyCode, loginId, phone);
        try {
            createAccount(academyCode, loginId, phone);
            requestRecoverCode(phone).andExpect(status().isOk());
            String code = latestCode(phone);
            String wrongCode = code.equals("000000") ? "111111" : "000000";

            for (int i = 0; i < 5; i++) {
                submitRecoverCode(phone, wrongCode)
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.error.code").value("VERIFICATION_CODE_INVALID"));
            }
            assertThat(latestAttemptCount(phone))
                    .as("실패한 대조가 커밋되지 않으면 상한은 영원히 도달하지 않는다")
                    .isEqualTo(5);

            submitRecoverCode(phone, code)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("VERIFICATION_CODE_INVALID"));
        } finally {
            deleteRecoverFixture(academyCode, loginId, phone);
        }
    }

    /**
     * 목표 문장 — 코드를 재발급하면 이전 미소비 코드가 함께 무효화된다.
     *
     * <p>단언을 응답이 아니라 DB 로 하는 이유 — 대조는 최신 1건만 보므로, 무효화를 빼도 옛 코드
     * 제출은 어차피 "최신과 값이 다름" 으로 403 이 된다. 즉 응답만 보면 이 조치가 있으나 없으나
     * 같다. 살아 있는 코드가 몇 건인지 세는 것만이 둘을 가른다 — 옛 코드가 유효한 채로 쌓이면
     * 상한(위 테스트)을 재발급으로 우회할 수 있다.
     */
    @Test
    void 코드를_재발급하면_이전_미소비_코드가_무효화된다() throws Exception {
        String phone = "010-7000-0020";
        createAccount("P2T4AUT20", "p2t4recreissu", phone);

        requestRecoverCode(phone).andExpect(status().isOk());
        String firstCode = latestCode(phone);
        requestRecoverCode(phone).andExpect(status().isOk());
        String secondCode = latestCode(phone);
        assertThat(secondCode).as("재발급이 실제로 새 코드를 만들어야 이 테스트가 성립한다").isNotEqualTo(firstCode);

        Integer aliveCodes = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM verification_code WHERE phone = ? AND consumed_at IS NULL",
                Integer.class, phone);
        assertThat(aliveCodes).as("재발급 뒤 살아 있는 코드는 최신 1건뿐이어야 한다").isEqualTo(1);
    }

    // ── §2.9 보조 ────────────────────────────────────────────────────────

    private ResultActions requestRecoverCode(String phone) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/recover")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\": \"password\", \"phone\": \"%s\"}".formatted(phone)));
    }

    private ResultActions submitRecoverCode(String phone, String code) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/recover")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\": \"password\", \"phone\": \"%s\", \"verification_code\": \"%s\"}"
                        .formatted(phone, code)));
    }

    private String latestCode(String phone) {
        return jdbcTemplate.queryForObject(
                "SELECT code FROM verification_code WHERE phone = ? ORDER BY created_at DESC, id DESC LIMIT 1",
                String.class, phone);
    }

    private int latestAttemptCount(String phone) {
        return jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM verification_code WHERE phone = ? ORDER BY created_at DESC, id DESC LIMIT 1",
                Integer.class, phone);
    }

    /**
     * 트랜잭션 밖에서 도는 테스트의 픽스처를 지운다 — 남기면 다음 실행이 UNIQUE 제약에서 실패한다.
     * {@code refresh_token} 은 {@code fk_refresh_token_account} 가 CASCADE 라 계정과 함께 지워지고,
     * {@code account} → {@code academy} 는 RESTRICT 라 순서를 지켜야 한다.
     */
    private void deleteRecoverFixture(String academyCode, String loginId, String phone) {
        jdbcTemplate.update("DELETE FROM verification_code WHERE phone = ?", phone);
        jdbcTemplate.update("DELETE FROM account WHERE login_id = ?", loginId);
        jdbcTemplate.update("DELETE FROM academy WHERE code = ?", academyCode);
    }
}
