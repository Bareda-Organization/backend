package src.backend.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.common.SeedFixtures;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * 퇴사한 관계자의 <b>재로그인</b>을 막는다 — ACAD-06 · API_SPEC §2.5·§6.7·§8.1 (Ruling 143).
 *
 * <p>refresh 토큰 무효화는 <b>그 순간 열려 있는 세션</b>만 끊는다. 비밀번호를 아는 퇴사자가 다시
 * 로그인하면 {@code role=staff} 권한을 그대로 되찾으므로, §6.7 이 요건으로 규정한 "퇴사 즉시 권한
 * 회수" 가 성립하지 않는다 — 관계자 계정은 학생 개인정보 전체에 접근한다(§6.7).
 *
 * <p><b>이 클래스는 거부측만큼 허용측을 본다.</b> "{@code academy_staff} 행이 없으면 거부" 로
 * 구현하면 학부모·기사·승인 대기 관계자가 <b>전부 로그인 불가</b>가 되는데, 그 사고는 거부측
 * 단언으로는 하나도 잡히지 않는다. 판정 대상은 <b>행이 있고 그 상태가 {@code inactive} 인 경우뿐</b>이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StaffEmploymentLoginTest {

    private static final long SYSTEM_ADMIN_ACCOUNT_ID = 1L;

    private static final String RAW_PASSWORD = "password1234!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // ── 거부측 ────────────────────────────────────────────────────────────

    /** 퇴사 처리된 관계자는 비밀번호가 맞아도 로그인할 수 없다(§2.5 · §8.1). */
    @Test
    void 퇴사_처리된_관계자는_다시_로그인할_수_없다() throws Exception {
        long accountId = 재직_관계자를_만든다("p3t3emp1");
        퇴사시킨다(accountId);

        로그인을_시도한다("p3t3emp1", RAW_PASSWORD)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AUTH_STAFF_INACTIVE"));
    }

    /**
     * 재직 확인은 <b>비밀번호 대조를 통과한 뒤</b>에 한다 — 틀린 비밀번호에는 여느 계정과 같은
     * {@code 401 INVALID_CREDENTIALS} 가 나가야 한다.
     *
     * <p>이 단언이 검사 순서를 고정하는 유일한 자리다. 대조 <b>앞</b>에 두면 아이디 하나만으로
     * "실재하고 퇴사한 관계자" 를 알려 주는 계정 열거 채널이 늘고, 그 탐색은 실패 카운터를 올리지
     * 않아 <b>횟수 제한도 받지 않는다.</b> 순서를 바꿔도 나머지 단언은 전부 통과한다.
     */
    @Test
    void 퇴사_관계자의_잘못된_비밀번호는_401_이라_아이디만으로_퇴사_여부가_드러나지_않는다() throws Exception {
        long accountId = 재직_관계자를_만든다("p3t3emp2");
        퇴사시킨다(accountId);

        로그인을_시도한다("p3t3emp2", "wrong-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
    }

    // ── 허용측 ────────────────────────────────────────────────────────────

    /** 재직 중인 관계자는 그대로 로그인된다 — 거부측만 두면 전 관계자를 막는 구현이 통과한다. */
    @Test
    void 재직_중인_관계자는_로그인할_수_있다() throws Exception {
        재직_관계자를_만든다("p3t3emp3");

        로그인을_시도한다("p3t3emp3", RAW_PASSWORD).andExpect(status().isOk());
    }

    /** 재입사(`status=active` 복귀)하면 로그인이 다시 열린다 — 거부가 영구적이지 않다는 축이다(§6.7). */
    @Test
    void 퇴사_관계자를_다시_active_로_되돌리면_로그인할_수_있다() throws Exception {
        long accountId = 재직_관계자를_만든다("p3t3emp4");
        퇴사시킨다(accountId);
        재직시킨다(accountId);

        로그인을_시도한다("p3t3emp4", RAW_PASSWORD).andExpect(status().isOk());
    }

    /**
     * {@code staff} 가 아닌 역할은 {@code academy_staff} 행이 애초에 없다 — 그 부재를 퇴사로 읽으면
     * <b>학부모·학생·기사·동승자 전원이 로그인 불가</b>가 된다.
     *
     * <p>서비스 전체가 멈추는 사고인데 거부측 단언으로는 하나도 잡히지 않는다. 역할 넷을 함께 보는
     * 이유는 판정이 역할 하나에만 걸린 구현을 나머지 셋이 잡기 때문이다.
     */
    @Test
    void staff_가_아닌_역할은_academy_staff_행이_없어도_로그인할_수_있다() throws Exception {
        for (String loginId : new String[] {SeedFixtures.PARENT_A1_LOGIN_ID, SeedFixtures.STUDENT_A4_LOGIN_ID,
                SeedFixtures.DRIVER_A1_LOGIN_ID, SeedFixtures.ESCORT_A1_LOGIN_ID}) {
            비밀번호를_아는_값으로_바꾼다(loginId);

            로그인을_시도한다(loginId, RAW_PASSWORD)
                    .andExpect(status().isOk());
        }
    }

    /**
     * 승인 대기 중인 관계자도 {@code academy_staff} 행이 없다 — 그 부재는 퇴사가 아니라 <b>아직 승인 전</b>이다.
     *
     * <p>막으면 §1.4 가 규정한 대기 화면 진입로(`GET /auth/signup-status`·`GET /me`)가 통째로 사라져
     * <b>관계자 가입 흐름 자체가 성립하지 않는다</b> — 승인을 기다리는 사람이 자기 상태를 볼 수단이
     * 부재해진다. 행 부재와 행이 {@code inactive} 인 것은 다른 사실이며, 앞은 §6.4 승인 큐의 축이다.
     */
    @Test
    void 승인_대기_중인_관계자는_academy_staff_행이_없어도_로그인할_수_있다() throws Exception {
        비밀번호를_아는_값으로_바꾼다(SeedFixtures.STAFF_PENDING_LOGIN_ID);

        로그인을_시도한다(SeedFixtures.STAFF_PENDING_LOGIN_ID, RAW_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("pending"));
    }

    // ── 도우미 ────────────────────────────────────────────────────────────

    private ResultActions 로그인을_시도한다(String loginId, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"login_id\":\"%s\",\"password\":\"%s\"}".formatted(loginId, password)));
    }

    /** 퇴사·재직 전환은 §6.7 엔드포인트를 실제로 거친다 — 두 기능이 이어져 있다는 것이 이 태스크의 요건이다. */
    private void 퇴사시킨다(long accountId) throws Exception {
        재직_상태를_바꾼다(accountId, "inactive");
    }

    private void 재직시킨다(long accountId) throws Exception {
        재직_상태를_바꾼다(accountId, "active");
    }

    private void 재직_상태를_바꾼다(long accountId, String status) throws Exception {
        mockMvc.perform(patch("/api/v1/admin/staff-accounts/" + accountId)
                        .header("Authorization", 메인_관리자_토큰())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"%s\"}".formatted(status)))
                .andExpect(status().isOk());
    }

    /**
     * 학원 1곳 + 재직 관계자 1명을 <b>실제 경로</b>로 만든다 — 학원 등록(§6.2) → 관계자 가입(§2.2) →
     * 메인 관리자 승인(§6.5). 학원마다 재직 1명 정원이라(§6.4) 계정마다 학원을 따로 만든다.
     *
     * <p>SQL 세 줄이었다가 바뀐 자리다(목표 10). SQL 은 {@code academy_staff} 행을 <b>직접</b> 넣어,
     * 승인이 그 행을 만들지 못하게 되어도 이 테스트는 그대로 초록이다 — 그러면 "퇴사한 관계자를 막는가"
     * 는 검사하면서 <b>애초에 재직 행이 생기는가</b> 는 아무도 검사하지 않는 상태가 된다. 승인 경로로
     * 만들면 그 둘이 한 시험 안에서 함께 선다.
     */
    private long 재직_관계자를_만든다(String loginId) throws Exception {
        String adminToken = 메인_관리자_토큰();
        MvcResult 학원 = mockMvc.perform(post("/api/v1/admin/academies")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"P3T3재직학원%s\", \"region\": \"서울\"}".formatted(loginId)))
                .andExpect(status().isCreated())
                .andReturn();
        long academyId = ((Number) JsonPath.read(본문(학원), "$.data.academy_id")).longValue();

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role": "staff", "login_id": "%s", "password": "%s", "name": "관계자%s",
                                 "phone": "010-0000-0000", "academy_id": "%d"}
                                """.formatted(loginId, RAW_PASSWORD, loginId, academyId)))
                .andExpect(status().isCreated());

        MvcResult 큐 = mockMvc.perform(get("/api/v1/admin/staff-signup-requests")
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andReturn();
        List<Integer> ids = JsonPath.read(본문(큐),
                "$.data.items[?(@.academy.id == '%d')].request_id".formatted(academyId));
        assertThat(ids).as("등록한 학원의 관계자 요청이 승인 큐에 1건 떠야 픽스처가 성립한다").hasSize(1);

        mockMvc.perform(post("/api/v1/admin/staff-signup-requests/" + ids.get(0) + "/decide")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accept\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.account_status").value("active"));

        return jdbcTemplate.queryForObject("SELECT id FROM account WHERE login_id = ?", Long.class, loginId);
    }

    private String 메인_관리자_토큰() {
        return "Bearer " + tokenProvider.createAccessToken(SYSTEM_ADMIN_ACCOUNT_ID, null, Role.SYSTEM_ADMIN,
                AccountStatus.ACTIVE);
    }

    private String 본문(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** 시드 계정의 비밀번호를 이 테스트가 아는 값으로 바꾼다 — 시드 해시가 담은 평문에 기대지 않는다. */
    private void 비밀번호를_아는_값으로_바꾼다(String loginId) {
        jdbcTemplate.update("UPDATE account SET password_hash = ? WHERE login_id = ?",
                passwordEncoder.encode(RAW_PASSWORD), loginId);
    }
}
