package src.backend.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.jayway.jsonpath.JsonPath;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.account.repository.AccountRepository;
import testsupport.gate.AccountStatusGateEndpoints;

/**
 * 계정 흐름을 <b>실제 엔드포인트</b>로 종단 검증한다(Phase 2 완료 조건 1·2·3) — 학원 검색부터
 * 로그아웃까지 한 흐름으로 밟고, 그 흐름이 만든 {@code pending}·{@code rejected} 토큰으로 계정 상태
 * 게이트(C-01 · API_SPEC §1.4)를 왕복한다.
 *
 * <p>세 조건을 한 클래스에 둔 이유는 조건 2·3 의 재료가 조건 1 의 흐름이기 때문이다 — 픽스처로 심은
 * 계정이 아니라 <b>1단계에서 실제로 검색한 학원에 2단계가 실제로 가입해 3단계가 실제로 로그인한</b>
 * 계정의 토큰이라야, 각 단계의 응답값이 다음 단계 입력으로 이어지는지를 함께 본다.
 *
 * <p>{@code AccountStatusGateInterceptorTest} 와 겹치지 않는다 — 그쪽은 테스트 전용 합성 컨트롤러
 * ({@code GateTestController})를 두드려 게이트의 <b>판정 로직</b>을 본다. 실제 엔드포인트가 게이트의
 * <b>경로 매핑</b>에서 빠지면 판정 로직은 멀쩡한 채로 그 경로만 통째로 열리는데, 그 사고는 여기처럼
 * 실제 경로를 두드려야만 드러난다. 애너테이션 <b>부착</b>을 세는 개수 단언도 그 애너테이션이
 * 런타임에 실제로 읽히는지는 보지 않으므로 이 갭을 메우지 못한다.
 *
 * <p>Phase 3 목표 6(Ruling 133)으로 거부측이 <b>허용 목록 밖 실제 핸들러 전부</b>로 확대됐다 —
 * 대상 목록과 그 목록이 낡지 않았는지 대조하는 수단은 {@code testsupport.gate.AccountStatusGateEndpoints}
 * 에 둔다.
 *
 * <p>클래스가 200줄({@code reference.md} §20.2)을 넘지만 나누지 않는다 — 위 이유로 세 조건이 흐름
 * 하나를 공유하고, 나누면 조건 2·3 이 다시 픽스처로 심은 토큰을 쓰게 된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthFlowIntegrationTest {

    private static final String RAW_PASSWORD = "password1234!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    /** 액추에이터도 같은 타입의 빈({@code controllerEndpointHandlerMapping})을 등록해 이름으로 가른다. */
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    /** 한 계정의 흐름 상태 — 각 단계가 다음 단계로 넘기는 값만 담는다. */
    private record Session(Long accountId, Long academyId, String loginId, String accessToken,
            String refreshToken) {
    }

    /**
     * 완료 조건 1 — 학원 검색 → 가입 → 로그인 → 가입 상태 조회 → 토큰 재발급 → 로그아웃을 한 흐름으로
     * 밟고, 각 단계의 응답값을 다음 단계 입력으로 넘긴다.
     *
     * <p>정본(docs/IMPLEMENTATION_PLAN Phase 2)은 "가입 → {@code pending} 조회 → 로그인" 순으로
     * 적었으나 실제 호출 순서는 <b>로그인이 먼저</b>다 — {@code GET /auth/signup-status} 는
     * {@code @AuthenticatedOnly} 라 토큰이 있어야 부를 수 있고, 가입 응답({@code SignupResponse})은
     * {@code account_status}·{@code requested_at}·{@code approver} 3필드뿐이라 토큰을 주지 않는다.
     * 문서의 나열 순서와 다르다는 이유로 되돌리지 말 것.
     *
     * <p>말미에 무효화된 refresh 로 재발급을 시도해 401 을 확인한다 — 이 단언이 없으면 로그아웃이
     * 아무것도 무효화하지 않고 204 만 돌려줘도 이 테스트가 통과한다.
     */
    @Test
    void 학원_검색부터_로그아웃까지_한_흐름으로_완주한다() throws Exception {
        Session session = searchSignupLogin("P2T6FLOWQQQQ", "p2t6flowqqqqq", "010-7000-0101");

        mockMvc.perform(get("/api/v1/auth/signup-status").header("Authorization", bearer(session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("pending"));

        MvcResult refreshed = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(session.refreshToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.access_token").exists())
                .andReturn();
        String rotatedRefresh = read(refreshed, "$.data.refresh_token");
        assertThat(read(refreshed, "$.data.access_token"))
                .as("재발급은 새 access 를 내려야 한다 — 같은 값이면 아무것도 재발급하지 않은 것이다")
                .isNotEqualTo(session.accessToken());
        assertThat(rotatedRefresh)
                .as("C-14 회전 — 재발급이 옛 refresh 를 그대로 돌려주면 탈취된 토큰이 영구 유효해진다")
                .isNotEqualTo(session.refreshToken());

        mockMvc.perform(post("/api/v1/auth/logout").header("Authorization", bearer(session))
                        .contentType(MediaType.APPLICATION_JSON).content(refreshBody(rotatedRefresh)))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(rotatedRefresh)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_EXPIRED"));
    }

    /**
     * 완료 조건 2 — {@code pending} 토큰은 허용 5개를 <b>각각</b> 통과하고, 허용 목록 밖의 실제
     * 엔드포인트에서는 {@code 403 AUTH_PENDING} 이다.
     *
     * <p>허용 5개를 골라서 부르지 않는 이유는 이 단언의 목적이 "허용 목록에서 하나가 빠지는 사고" 를
     * 잡는 것이기 때문이다 — 고르면 고르지 않은 것이 곧 사고 지점이 된다.
     *
     * <p>거부측은 허용 밖 실제 핸들러 <b>전부</b>다({@code AccountStatusGateEndpoints}) — 정본의
     * 완료 조건 문면이 "허용 5개 외 API 호출 시 <b>전부</b> 403" 이라 일부만 골라 두드리면 고르지 않은
     * 것이 곧 사고 지점이 된다. Phase 2 시점에는 프로덕션 핸들러가 12개뿐이라 허용 밖이
     * {@code POST /auth/password}·{@code POST /auth/signup/reapply} 둘이었고 그래서 거부측이 2개였다 —
     * Phase 3 이 핸들러를 12개 늘려(총 24개) 그 근거가 소멸했으므로 목록을 함께 늘렸다(Ruling 133,
     * Phase 3 목표 6). Phase 5 가 학생 관리 5개 · 차량·매니저 7개 · 자녀 연결 4개 · 스케줄 4개 ·
     * 회차와 배치 4개를 같은 이유로 더했다. 개수를 채우려고 엔드포인트를 새로 만들지 않는다.
     */
    @Test
    void pending_토큰은_허용_5개를_통과하고_허용_밖_실제_엔드포인트_전부에서_403_AUTH_PENDING_이다() throws Exception {
        Session session = searchSignupLogin("P2T6PENDQQQQ", "p2t6pendingqq", "010-7000-0102");

        callAllowedFive(session, "pending", "fcm-p2t6-pending");

        거부측을_전부_두드린다(session, AccountStatusGateEndpoints.DENIED_WHEN_PENDING, "AUTH_PENDING");
    }

    /**
     * 목표 6 — 거부측 목록이 <b>허용 목록 밖 실제 핸들러 전부</b>와 정확히 같은지 런타임 매핑으로 대조한다.
     *
     * <p>손으로 적은 목록만 두면 다음 Phase 가 엔드포인트를 늘렸을 때 위 두 왕복 테스트는 여전히 초록인
     * 채로 검사 범위만 좁아진다 — "전부 403" 이라는 문면과 실제 검사 범위가 벌어지는 그 사고를 여기서
     * 잡는다. 목록에 없는 새 핸들러가 생기거나(검사 누락) 목록에만 있고 실제로 사라지면(엔드포인트 실종)
     * 둘 다 실패한다.
     *
     * <p>전체 핸들러 수 단언을 함께 두는 이유는 필터가 대상을 통째로 놓쳐도 두 집합이 나란히 비어
     * 일치할 수 있기 때문이다 — 그 경우 "규칙 준수" 와 "검사 대상 없음" 이 구별되지 않는다.
     */
    @Test
    void 거부측_목록이_허용_목록_밖_실제_핸들러_전부와_일치한다() {
        List<String> all = AccountStatusGateEndpoints.productionEndpoints(handlerMapping, handlerMethod -> true);
        assertThat(all)
                .as("프로덕션 핸들러 81개 — 늘었는데 이 단언만 고치면 거부측 목록이 낡는다. "
                        + "Phase 2 의 12 + Phase 3 의 12 + Phase 5 의 26"
                        + "(학생 5 + 차량·매니저 7 + 자녀 연결 4 + 스케줄 4 + 회차·배치 4 + 요일별 주소 2)"
                        + " + Phase 6 의 6(고정 노선 CRUD 5 + 순서 최적화 1)"
                        + " + Phase 8 의 9(승인 목록·상세·결정 3 + 강제 추가 1 + 경유 지점 배포·해제 2"
                        + " + 탑승 토글 1 + 변경 신청 등록·조회 2)"
                        + " + Phase 9 의 10(운행 시작·도착·변경 확인 3 + 매니저 앱 조회 3 + 관계자 웹 명단 1 + 승하차·되돌리기 2 + 내비 1)"
                        + " + Phase 10 의 3(위치 송신 1 + 학부모 실시간 위치·상세 노선 조회 2)"
                        + " + Phase 11 T1 의 3(미승차 연락 이력 등록 1 + 학원 설정 조회·수정 2)")
                .hasSize(81);

        assertThat(AccountStatusGateEndpoints.productionEndpoints(
                handlerMapping, AccountStatusGateEndpoints::deniedWhenPending))
                .as("pending 거부측 목록이 @PublicEndpoint·@AllowedWhenPending 밖 실제 핸들러 전부와 같아야 한다")
                .containsExactlyInAnyOrderElementsOf(AccountStatusGateEndpoints.DENIED_WHEN_PENDING);

        assertThat(AccountStatusGateEndpoints.productionEndpoints(
                handlerMapping, AccountStatusGateEndpoints::deniedWhenRejected))
                .as("rejected 거부측 목록이 허용 6개·공개 5개 밖 실제 핸들러 전부와 같아야 한다")
                .containsExactlyInAnyOrderElementsOf(AccountStatusGateEndpoints.DENIED_WHEN_REJECTED);
    }

    /**
     * 완료 조건 3 — {@code rejected} 토큰은 허용 6개({@code pending} 의 5개 + 재신청)를 각각 통과하고,
     * 허용 밖에서는 {@code 403 AUTH_REJECTED} 다.
     *
     * <p>거부 코드가 {@code AUTH_PENDING} 이 아니라 {@code AUTH_REJECTED} 인 것까지 본다(API_SPEC
     * §8.1) — 두 상태가 같은 코드를 쓰면 클라이언트가 "승인 대기" 와 "거절" 을 구별할 수단이
     * 부재해져 대기 화면과 재신청 화면을 가려 그릴 수 없다.
     *
     * <p>재신청은 계정을 {@code rejected → pending} 으로 되돌리므로 6개 중 마지막에 부른다 — 거부측
     * 전부를 먼저 두드리는 것도 같은 이유다. 상태가 {@code pending} 으로 돌아간 뒤에 두드리면 거부
     * 코드가 {@code AUTH_PENDING} 이 되어 이 테스트가 검사하려는 것이 사라진다.
     */
    @Test
    void rejected_토큰은_허용_6개를_통과하고_허용_밖_실제_엔드포인트_전부에서_403_AUTH_REJECTED_이다() throws Exception {
        Session pending = searchSignupLogin("P2T6REJTQQQQ", "p2t6rejectedq", "010-7000-0103");
        forceRejected(pending.accountId());
        Session session = login(pending, "rejected");

        callAllowedFive(session, "rejected", "fcm-p2t6-rejected");

        거부측을_전부_두드린다(session, AccountStatusGateEndpoints.DENIED_WHEN_REJECTED, "AUTH_REJECTED");

        mockMvc.perform(post("/api/v1/auth/signup/reapply").header("Authorization", bearer(session))
                        .contentType(MediaType.APPLICATION_JSON).content(reapplyBody(session.academyId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("pending"));
    }

    /**
     * 거부측 엔드포인트를 하나도 빠뜨리지 않고 두드려 전부 {@code 403} + 지정한 오류 코드인지 본다.
     *
     * <p>실패를 그대로 던지지 않고 <b>어느 엔드포인트</b>인지 붙여 다시 던진다 — 넘겨받은 목록을 한 루프로 도는
     * 구조라 원래 실패 메시지만으로는 어느 경로가 열렸는지 알 수 없다.
     *
     * <p>본문은 {@code POST /auth/password} 만 실제 형식을 쓰고 나머지는 빈 객체다. 게이트는
     * {@code preHandle} 이라 본문 해석보다 먼저 돌아 거부측에서는 본문에 닿지 않으며, 게이트가 사라지면
     * 응답이 {@code 400}·{@code 403 FORBIDDEN} 으로 바뀌어 어느 쪽이든 이 단언이 문다.
     */
    private void 거부측을_전부_두드린다(Session session, List<String> endpoints, String expectedCode) throws Exception {
        for (String endpoint : endpoints) {
            try {
                mockMvc.perform(request(AccountStatusGateEndpoints.httpMethodOf(endpoint),
                                AccountStatusGateEndpoints.uriOf(endpoint))
                                .header("Authorization", bearer(session))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(denialProbeBody(endpoint)))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.error.code").value(expectedCode));
            } catch (AssertionError e) {
                throw new AssertionError("허용 목록 밖 %s 가 %s 로 거부되지 않았다".formatted(endpoint, expectedCode), e);
            }
        }
    }

    /** 거부측 본문 — 게이트가 본문에 닿지 않으므로 형식 검증이 있는 {@code /auth/password} 외에는 빈 객체로 충분하다. */
    private static String denialProbeBody(String endpoint) {
        return endpoint.equals("POST /auth/password") ? passwordChangeBody() : "{}";
    }

    /** {@code pending}·{@code rejected} 가 공유하는 허용 5개를 하나도 빠뜨리지 않고 왕복한다. */
    private void callAllowedFive(Session session, String expectedStatus, String deviceToken) throws Exception {
        String bearer = bearer(session);

        mockMvc.perform(get("/api/v1/auth/signup-status").header("Authorization", bearer))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/me").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(expectedStatus));
        mockMvc.perform(post("/api/v1/me/devices").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(deviceBody(deviceToken)))
                .andExpect(status().isCreated());
        mockMvc.perform(delete("/api/v1/me/devices/{token}", deviceToken).header("Authorization", bearer))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/auth/logout").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(refreshBody(session.refreshToken())))
                .andExpect(status().isNoContent());
    }

    /** 검색 응답의 학원 식별자를 가입 입력으로, 가입에 쓴 자격을 로그인 입력으로 넘겨 흐름 1~3단계를 밟는다. */
    private Session searchSignupLogin(String academyCode, String loginId, String phone) throws Exception {
        academyRepository.save(Academy.register(academyCode, "학원" + academyCode + "통합", "서울", null, null));

        MvcResult searched = mockMvc.perform(get("/api/v1/academies/search").param("q", academyCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andReturn();
        String academyId = read(searched, "$.data.items[0].id");

        mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody(academyId, loginId, phone)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.account_status").value("pending"));

        Long accountId = accountRepository.findByLoginId(loginId).orElseThrow().getId();
        assertThat(accountRepository.findById(accountId).orElseThrow().getAcademyId())
                .as("검색 응답의 학원에 실제로 가입돼야 1단계 값이 2단계로 이어진 것이다")
                .isEqualTo(Long.valueOf(academyId));

        return login(new Session(accountId, Long.valueOf(academyId), loginId, null, null), "pending");
    }

    /** 가입에 쓴 자격으로 로그인해 access·refresh 를 받은 새 {@link Session} 을 돌려준다. */
    private Session login(Session base, String expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"login_id": "%s", "password": "%s"}
                                """.formatted(base.loginId(), RAW_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(expectedStatus))
                .andReturn();
        return new Session(base.accountId(), base.academyId(), base.loginId(),
                read(result, "$.data.access_token"), read(result, "$.data.refresh_token"));
    }

    /**
     * 거절 상태는 승인 절차(Task 5 범위)를 거쳐야 만들어져 정적 팩토리로 곧장 만들 수 없다 — raw
     * UPDATE 로 전이시킨 뒤 반드시 영속성 컨텍스트를 비운다. 비우지 않으면 가입이 1차 캐시에 올려 둔
     * {@code Account} 가 그대로 반환돼 상태가 갱신되지 않은 것처럼 보인다.
     */
    private void forceRejected(Long accountId) {
        jdbcTemplate.update("UPDATE account SET status = 'rejected' WHERE id = ?", accountId);
        entityManager.clear();
    }

    private static String bearer(Session session) {
        return "Bearer " + session.accessToken();
    }

    private static String read(MvcResult result, String jsonPath) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), jsonPath);
    }

    private static String signupBody(String academyId, String loginId, String phone) {
        return """
                {"role": "parent", "login_id": "%s", "password": "%s", "name": "흐름테스트",
                 "phone": "%s", "academy_id": "%s"}
                """.formatted(loginId, RAW_PASSWORD, phone, academyId);
    }

    private static String refreshBody(String refreshToken) {
        return "{\"refresh_token\": \"%s\"}".formatted(refreshToken);
    }

    private static String reapplyBody(Long academyId) {
        return "{\"academy_id\": \"%d\"}".formatted(academyId);
    }

    private static String passwordChangeBody() {
        return "{\"current_password\": \"%s\", \"new_password\": \"newpassword1234!\"}".formatted(RAW_PASSWORD);
    }

    private static String deviceBody(String deviceToken) {
        return """
                {"token": "%s", "platform": "android", "device_id": "%s-device"}
                """.formatted(deviceToken, deviceToken);
    }
}
