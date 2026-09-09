package src.backend.academy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.common.SeedFixtures;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 학원 비활성화(ACAD-04, API_SPEC §6.3)의 <b>경계</b> — 신규 가입만 막고 기존 로그인은 유지한다.
 *
 * <p>Phase 3 완료 조건 4를 그대로 옮긴 것이며, <b>첫 단언이 이 조건의 전부</b>다. 그것이 없으면
 * "비활성화 = 전면 차단" 으로 구현해도 나머지 둘(검색 제외·신규 가입 차단)만으로 통과하고,
 * 그 구현은 운행 중인 기사를 로그아웃시킨다. 세 단언을 한 클래스에 묶은 이유도 같다 — 셋이 함께
 * 있어야 "무엇을 막고 무엇을 막지 않는가" 의 경계가 보인다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AcademyDeactivationTest {

    private static final String RAW_PASSWORD = "password1234!";

    /** 시드 계정의 비밀번호 — local 프로파일의 Flyway placeholder 가 이 평문의 해시를 심는다. */
    private static final String SEED_PASSWORD = "password";

    private static final String ACADEMY_NAME = "P3T1비활성화학원RR";

    private static final String STAFF_LOGIN_ID = "p3t1deactstaff";

    private static final String DRIVER_LOGIN_ID = "p3t1deactdriver";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @PersistenceContext
    private EntityManager entityManager;

    private Long academyId;

    /**
     * 활성 학원 1곳과 그 학원 소속의 <b>활성 기사 계정</b> 1개 — 비활성화 뒤에도 살아 있어야 하는 쪽이다.
     *
     * <p><b>저장소·raw UPDATE 로 심지 않고 실제 경로를 그대로 탄다</b>(목표 10). 옛 픽스처는 계정을
     * 만들고 {@code UPDATE account SET status='active'} 로 활성화했는데, 그것은 <b>AUTH-11 이 있을 수
     * 없다고 규정한 상태</b>다 — 승인은 기사에게 {@code link.manager_id} 를 요구하므로(§5.2) 매니저
     * 레코드에 연결되지 않은 활성 기사 계정은 운영에서 만들어질 수 없다. 그런 계정으로 로그인 경계를
     * 재면 운영에서 나올 수 없는 데이터를 검사하는 셈이다.
     *
     * <p>그래서 학원 등록(§6.2) → 관계자 가입·메인 관리자 승인(§6.5) → 관계자 로그인 →
     * <b>매니저 등록(MGR-02)</b> → 기사 가입 → 관계자 승인({@code link.manager_id}) 순으로 만든다.
     * 학원마다 재직 관계자가 1명이라(§6.4 정원) 이 학원의 승인자는 이 학원 관계자여야 한다.
     */
    @BeforeEach
    void 활성_학원과_기존_계정을_만든다() throws Exception {
        String adminToken = 메인_관리자_토큰();
        MvcResult 학원 = mockMvc.perform(post("/api/v1/admin/academies")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"%s\", \"region\": \"울산\"}".formatted(ACADEMY_NAME)))
                .andExpect(status().isCreated())
                .andReturn();
        academyId = ((Number) JsonPath.read(본문(학원), "$.data.academy_id")).longValue();

        가입한다("staff", STAFF_LOGIN_ID, "010-0000-2003");
        mockMvc.perform(post("/api/v1/admin/staff-signup-requests/" + 관계자_요청_식별자(adminToken) + "/decide")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accept\": true}"))
                .andExpect(status().isOk());

        String staffToken = "Bearer " + JsonPath.<String>read(
                본문(로그인한다(STAFF_LOGIN_ID, RAW_PASSWORD).andExpect(status().isOk()).andReturn()),
                "$.data.access_token");

        MvcResult 매니저 = mockMvc.perform(post("/api/v1/staff/managers")
                        .header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"재직기사\", \"phone\": \"010-0000-2001\", \"role\": \"driver\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        long managerId = ((Number) JsonPath.read(본문(매니저), "$.data.id")).longValue();

        가입한다("driver", DRIVER_LOGIN_ID, "010-0000-2001");
        mockMvc.perform(post("/api/v1/staff/signup-requests/" + 기사_요청_식별자(staffToken) + "/decide")
                        .header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accept\": true, \"link\": {\"manager_id\": %d}}".formatted(managerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.account_status").value("active"));
        entityManager.flush();
    }

    // ── 픽스처 도우미 ─────────────────────────────────────────────────────

    private String 메인_관리자_토큰() {
        return "Bearer " + tokenProvider.createAccessToken(1L, null, Role.SYSTEM_ADMIN, AccountStatus.ACTIVE);
    }

    private ResultActions 로그인한다(String loginId, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"login_id\": \"%s\", \"password\": \"%s\"}".formatted(loginId, password)));
    }

    private void 가입한다(String role, String loginId, String phone) throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role": "%s", "login_id": "%s", "password": "%s", "name": "비활성화-%s",
                                 "phone": "%s", "academy_id": "%d"}
                                """.formatted(role, loginId, RAW_PASSWORD, loginId, phone, academyId)))
                .andExpect(status().isCreated());
    }

    /** 메인 관리자 큐는 전 학원 범위라 <b>방금 만든 학원</b>의 요청만 골라낸다. */
    private long 관계자_요청_식별자(String adminToken) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/admin/staff-signup-requests")
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andReturn();
        List<Integer> ids = JsonPath.read(본문(result),
                "$.data.items[?(@.academy.id == '%d')].request_id".formatted(academyId));
        assertThat(ids)
                .as("등록한 학원의 관계자 요청이 메인 관리자 큐에 1건 떠야 픽스처가 성립한다").hasSize(1);
        return ids.get(0).longValue();
    }

    private long 기사_요청_식별자(String staffToken) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/staff/signup-requests")
                        .header("Authorization", staffToken))
                .andExpect(status().isOk())
                .andReturn();
        List<Integer> ids = JsonPath.read(본문(result), "$.data.items[*].request_id");
        assertThat(ids)
                .as("이 학원의 승인 큐에는 기사 요청 1건만 있어야 한다").hasSize(1);
        return ids.get(0).longValue();
    }

    private String 본문(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * 비활성화 뒤에도 그 학원 <b>기존 계정</b>의 로그인은 200 이다.
     *
     * <p>비활성화가 계정까지 끊으면 운행 중인 기사가 그 자리에서 로그아웃된다 — 학원 운영을 멈추는
     * 조치가 학생을 태우고 달리는 차량의 앱을 함께 멈추는 형태가 된다.
     */
    @Test
    void 학원을_비활성화해도_그_학원_기존_계정의_로그인은_200_이다() throws Exception {
        비활성화한다();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login_id\":\"%s\",\"password\":\"%s\"}".formatted(DRIVER_LOGIN_ID, RAW_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.access_token").isNotEmpty());
    }

    /**
     * 비활성 학원 소속 <b>관계자</b>의 로그인도 200 이다 — 위 단언의 기사 축과 짝이다(Ruling 146-3).
     *
     * <p>기사 계정 하나로는 이 조건을 다 덮지 못한다. {@code role='staff'} 만 로그인 경로에서
     * {@code academy_staff} 재직 검사를 <b>추가로</b> 통과해야 하기 때문이다(AUTH_STAFF_INACTIVE,
     * Ruling 143) — 그 검사가 학원 상태까지 보게 되면 <b>비활성 학원의 재직 관계자가 전원 로그인
     * 불가</b>가 되는데, 기사 축 단언은 그 경로를 지나지 않아 초록으로 남는다.
     *
     * <p>학원 비활성화(T1)와 재직 검사(T3)의 교차점이라 어느 쪽도 자기 범위로 보지 않았던 자리다.
     *
     * <p>재료는 자체 픽스처가 아니라 시드 {@code staffC} 다 — 세 성질을 동시에 갖춘 계정이라야
     * 이 단언이 성립하고, 시드가 이미 그것을 보유한다(직접 확인: 학원 3 {@code BARAEDA-C} 가
     * {@code inactive} · 계정 {@code staffC} 가 {@code role=staff status=active} · {@code academy_staff}
     * 에 그 계정의 {@code active} 행). {@code SeedFixturesContractTest} 가 이 성질들을 별도로 고정한다.
     */
    @Test
    void 비활성_학원_소속_재직_관계자의_로그인도_200_이다() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login_id\":\"%s\",\"password\":\"%s\"}"
                                .formatted(SeedFixtures.STAFF_C_LOGIN_ID, SEED_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("active"))
                .andExpect(jsonPath("$.data.access_token").isNotEmpty());
    }

    /** 비활성화하면 가입용 학원 검색에서 사라진다 — 신규 가입자가 고를 수 없어야 한다(§2.1 · §6.3). */
    @Test
    void 학원을_비활성화하면_가입용_학원_검색_결과에서_사라진다() throws Exception {
        mockMvc.perform(get("/api/v1/academies/search").param("q", ACADEMY_NAME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1));

        비활성화한다();

        mockMvc.perform(get("/api/v1/academies/search").param("q", ACADEMY_NAME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    /**
     * 비활성 학원을 지정한 회원가입은 {@code 404 ACADEMY_NOT_FOUND} 다.
     *
     * <p>막연한 4xx 가 아니라 이 코드인 근거는 에러 사전이 {@code ACADEMY_NOT_FOUND} 를 <b>"미등록 ·
     * 비활성 학원 지정"</b> 으로 정의하기 때문이다(§8.5) — 검색에서 이미 사라진 학원이라 클라이언트
     * 입장에서는 없는 학원과 구별되지 않는 것이 옳다.
     */
    @Test
    void 비활성_학원을_지정한_회원가입_요청은_404_ACADEMY_NOT_FOUND_로_거부된다() throws Exception {
        비활성화한다();

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "role": "parent",
                                  "login_id": "p3t1deactsignup",
                                  "password": "password1234",
                                  "name": "가입시도",
                                  "phone": "010-0000-2002",
                                  "academy_id": "%d"
                                }
                                """.formatted(academyId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ACADEMY_NOT_FOUND"));
    }

    /** 비활성화는 관리자 API 를 그대로 탄다 — 테스트가 저장소로 상태를 심으면 §6.3 경로를 아무도 밟지 않는다. */
    private void 비활성화한다() throws Exception {
        String adminToken = "Bearer "
                + tokenProvider.createAccessToken(1L, null, Role.SYSTEM_ADMIN, AccountStatus.ACTIVE);
        mockMvc.perform(patch("/api/v1/admin/academies/" + academyId)
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"inactive\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("inactive"));
    }
}
