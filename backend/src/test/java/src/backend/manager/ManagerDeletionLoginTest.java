package src.backend.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import src.backend.global.common.SeedFixtures;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 매니저를 삭제(MGR-04, {@code deleted_at})한 뒤 <b>그 계정의 로그인</b>이 어떻게 되는가 — Ruling 891 이 고정한다.
 *
 * <p><b>확정 — 비밀번호 대조를 통과한 뒤 {@code 403 AUTH_STAFF_INACTIVE} 다.</b> 옛 판정(Ruling 148)은 "로그인은 200,
 * 재직 검사를 매니저로 일반화하지 않는다" 였다. 그러나 Ruling 880 이 삭제된 매니저의 모든 요청을
 * {@code 401 TOKEN_EXPIRED} 로 막은 뒤로는 200 이 곧바로 401 반복이 됐다 — 로그인은 되는데 첫 요청부터 만료로
 * 읽히고, 앱은 다시 로그인하기를 되풀이한다. 그래서 막는 자리를 로그인 단계로 옮겼고, 오류 코드·서버 메시지는 퇴사한
 * 관계자와 같은 것을 쓴다(새 코드 없음).
 *
 * <p>대조 <b>뒤에</b> 거절하는 이유는 {@code LoginSettlement} 의 자바독 그대로다 — 앞에 두면 아이디 하나만으로
 * "삭제된 매니저의 계정" 을 알려 주는 열거 통로가 생긴다. 틀린 비밀번호는 지금처럼 {@code 401 INVALID_CREDENTIALS} 다.
 *
 * <p>대상은 <b>매니저 행이 있고 {@code deleted_at} 이 채워진 계정뿐</b>이다. 매니저 행이 아직 없는(연결 전) 계정은
 * 로그인된다 — 부재를 삭제로 읽으면 가입 승인을 기다리는 사람이 대기 화면(§1.4)에 닿지 못한다.
 *
 * <p>같은 판정을 두 군데에 쓰지 않는다 — 로그인 거절은 요청마다 쓰는 {@code AccountStanding} 을 그대로 부른다.
 * 한 계정에 매니저 행이 둘 생길 수도 없다({@code uk_manager_account_id} 는 {@code deleted_at} 과 무관하게
 * {@code account_id} 를 유일하게 묶는다) — 다시 등록한 매니저는 새 행·새 가입 계정이다.
 *
 * <p>거부측의 짝은 {@code StaffEmploymentLoginTest} 다 — 그쪽이 {@code academy_staff} 를, 이쪽이 매니저를 맡고
 * 같은 코드로 답한다.
 *
 * <p>재료를 SQL 로 심지 않고 <b>실제 등록 경로</b>로 만든다(목표 10 의 취지) — 매니저는 MGR-02 로,
 * 계정 ↔ 매니저 연결은 {@code §5.2} 가입 승인의 {@code link.manager_id} 로 만든다. 그 두 경로를
 * 거치지 않으면 {@code manager.account_id} 가 실제로 채워지는지가 이 시험의 전제에서 빠진다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ManagerDeletionLoginTest {

    /** 시드 계정의 비밀번호 — local 프로파일의 Flyway placeholder 가 이 평문의 해시를 심는다. */
    private static final String SEED_PASSWORD = "password";

    private static final String NEW_PASSWORD = "password1234!";

    private static final long ACADEMY_A_ID = Long.parseLong(SeedFixtures.ACADEMY_A_ID);

    private static final String DRIVER_LOGIN_ID = "p5t7deldriver";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    private String staffToken;

    private long managerId;

    @BeforeEach
    void 매니저와_그_매니저에_연결된_기사_계정을_실제_경로로_만든다() throws Exception {
        staffToken = 토큰(로그인한다(SeedFixtures.STAFF_A_LOGIN_ID, SEED_PASSWORD)
                .andExpect(status().isOk())
                .andReturn());

        MvcResult 등록 = mockMvc.perform(post("/api/v1/staff/managers")
                        .header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "P5T7삭제기사", "phone": "010-7777-9001", "role": "driver"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        managerId = Long.parseLong(JsonPath.read(본문(등록), "$.data.id"));

        가입한다(DRIVER_LOGIN_ID);
        long 요청 = 요청_식별자(DRIVER_LOGIN_ID);
        mockMvc.perform(post("/api/v1/staff/signup-requests/" + 요청 + "/decide")
                        .header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accept\": true, \"link\": {\"manager_id\": %d}}".formatted(managerId)))
                .andExpect(status().isOk());

        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM manager WHERE id = ? AND account_id = "
                        + "(SELECT id FROM account WHERE login_id = ?)", Integer.class, managerId, DRIVER_LOGIN_ID))
                .as("가입 승인이 매니저 레코드에 계정을 붙이지 못하면 이 시험은 아무것도 재지 않는다(AUTH-11)")
                .isEqualTo(1);
    }

    /**
     * 매니저를 삭제하면 맞는 비밀번호로도 로그인이 {@code 403 AUTH_STAFF_INACTIVE} 다(Ruling 891).
     *
     * <p>삭제 <b>전</b>은 200 으로 함께 두드린다. 뒤쪽만 보면 "기사 로그인은 원래 안 된다" 와 구별되지 않아
     * 삭제가 무엇을 바꿨는지 판정할 수단이 부재해진다.
     */
    @Test
    void 매니저를_삭제하면_그_계정의_로그인은_403_AUTH_STAFF_INACTIVE_이다() throws Exception {
        로그인한다(DRIVER_LOGIN_ID, NEW_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("active"));

        매니저를_삭제한다();

        로그인한다(DRIVER_LOGIN_ID, NEW_PASSWORD)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AUTH_STAFF_INACTIVE"));
    }

    /**
     * 틀린 비밀번호는 삭제 여부와 상관없이 {@code 401 INVALID_CREDENTIALS} 다 — 거절이 대조 <b>앞</b>에 있으면 아이디만으로
     * 삭제된 매니저의 계정을 가려내는 열거 통로가 되고, 그 탐색은 실패 카운터도 올리지 않는다.
     */
    @Test
    void 삭제된_매니저의_계정도_틀린_비밀번호는_401_INVALID_CREDENTIALS_이다() throws Exception {
        매니저를_삭제한다();

        로그인한다(DRIVER_LOGIN_ID, "wrong-password-0!")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
    }

    /** 매니저 행에 아직 연결되지 않은 계정(가입 승인 전)은 지금처럼 로그인된다 — 부재를 삭제로 읽지 않는다. */
    @Test
    void 매니저_행에_연결되지_않은_계정의_로그인은_200_이다() throws Exception {
        String unlinked = "p5t7unlinked";
        가입한다(unlinked);

        로그인한다(unlinked, NEW_PASSWORD).andExpect(status().isOk());
    }

    /**
     * 삭제가 실제로 {@code deleted_at} 을 채우는지 함께 본다 — 채우지 않았다면 위 단언은 "지워지지
     * 않은 매니저의 계정이 로그인된다" 를 잰 것이라 판정 대상을 통째로 비껴간다.
     */
    @Test
    void 삭제된_매니저는_deleted_at_이_채워지고_목록에서_빠진다() throws Exception {
        매니저를_삭제한다();

        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM manager WHERE id = ? AND deleted_at IS NOT NULL", Integer.class, managerId))
                .as("soft delete 가 아니라 아무것도 하지 않은 구현이면 여기서 0 이다")
                .isEqualTo(1);

        MvcResult 목록 = mockMvc.perform(get("/api/v1/staff/managers")
                        .header("Authorization", staffToken).param("q", "P5T7삭제기사"))
                .andExpect(status().isOk())
                .andReturn();
        List<Object> items = JsonPath.read(본문(목록), "$.data.items[?(@.id == %d)]".formatted(managerId));
        assertThat(items).as("삭제한 매니저가 목록에 남으면 관계자 화면에서 지운 사람이 계속 보인다").isEmpty();
    }

    // ── 도우미 ────────────────────────────────────────────────────────────

    private void 매니저를_삭제한다() throws Exception {
        mockMvc.perform(delete("/api/v1/staff/managers/" + managerId)
                        .header("Authorization", staffToken))
                .andExpect(status().isNoContent());
    }

    private ResultActions 로그인한다(String loginId, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"login_id\": \"%s\", \"password\": \"%s\"}".formatted(loginId, password)));
    }

    private String 토큰(MvcResult result) throws Exception {
        return "Bearer " + JsonPath.read(본문(result), "$.data.access_token");
    }

    private void 가입한다(String loginId) throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role": "driver", "login_id": "%s", "password": "%s", "name": "삭제-%s",
                                 "phone": "010-5000-9001", "academy_id": "%d"}
                                """.formatted(loginId, NEW_PASSWORD, loginId, ACADEMY_A_ID)))
                .andExpect(status().isCreated());
    }

    private long 요청_식별자(String loginId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/staff/signup-requests")
                        .header("Authorization", staffToken))
                .andExpect(status().isOk())
                .andReturn();
        String name = jdbcTemplate.queryForObject(
                "SELECT name FROM account WHERE login_id = ?", String.class, loginId);
        List<String> ids = JsonPath.read(본문(result),
                "$.data.items[?(@.name == '%s')].request_id".formatted(name));
        assertThat(ids).as("관계자 큐에 %s 의 요청이 정확히 1건 떠야 한다", loginId).hasSize(1);
        return Long.parseLong(ids.get(0));
    }

    private String 본문(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
