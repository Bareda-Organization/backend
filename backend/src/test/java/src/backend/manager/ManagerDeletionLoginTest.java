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
 * 매니저를 삭제(MGR-04, {@code deleted_at})한 뒤 <b>그 계정의 로그인</b>이 어떻게 되는가 — Phase 5 가
 * 판정해 고정하는 축이다(이월 Ruling 148).
 *
 * <p><b>확정 — 로그인은 그대로 200 이다. 재직 검사를 매니저로 일반화하지 않는다.</b> 근거 셋.
 *
 * <ol>
 *   <li><b>거부의 근거가 될 코드가 정본에 부재하다.</b> {@code AUTH_STAFF_INACTIVE} 는 {@code §8.1} 이
 *       "{@code academy_staff.status='inactive'} 관계자 계정의 로그인" 으로 <b>좁혀 정의</b>한 코드이고,
 *       삭제된 매니저를 가리키는 코드는 {@code §8.1} 어디에도 없다. 새 403 을 지어내는 것은 사양에
 *       근거가 부재한 클라이언트 계약을 만드는 일이다.</li>
 *   <li><b>정본이 그 강제를 다른 층에 맡긴다.</b> {@code §5.2}(AUTH-11)는 "연결 부재 계정은 <b>데이터
 *       접근 불가</b>" 라고 적는다 — 로그인 거부가 아니라 접근 거부다. 로그인은 계정 상태 게이트
 *       ({@code §1.4})의 자리이고, {@code pending} 계정조차 대기 화면을 보려면 로그인해야 하므로
 *       <b>레코드 연결 여부를 재는 관문으로는 애초에 맞지 않는다.</b></li>
 *   <li><b>관계자 쪽은 정본이 따로 요구해서 생긴 예외다.</b> {@code §6.7} 이 "퇴사 즉시 권한 회수" 를
 *       요건으로 적고 그 근거로 "관계자 계정은 학생 개인정보 전체에 접근" 을 든다. 매니저에는 그
 *       문장이 부재하다 — 없는 요건을 유추로 만들면 그것이 곧 사양이 된다.</li>
 * </ol>
 *
 * <p>⚠ <b>정본이 침묵하는 것은 "삭제된 매니저의 계정이 매니저 앱 자료에 닿는가" 쪽이다.</b> 위 ②가
 * 그 강제를 접근 층에 맡기는데, <b>매니저 앱 엔드포인트는 Phase 7 소유라 이 Phase 에 존재하지
 * 않는다.</b> 즉 지금은 막을 대상 자체가 부재하고, 그것을 검사하는 단언도 여기 둘 수 없다. Phase 7 이
 * 매니저 앱 경로를 만들 때 <b>"삭제된 매니저의 계정은 자기 회차·명단을 볼 수 없다" 를 그 Phase 의
 * 조건으로 받아야 한다</b> — 받지 않으면 ②의 근거가 근거로만 남는다.
 *
 * <p>거부측의 짝은 {@code StaffEmploymentLoginTest} 다 — 그쪽이 {@code academy_staff} 축을, 이쪽이
 * 매니저 축을 맡는다. 두 축이 <b>서로 다른 답</b>을 갖는 것이 이 판정의 내용이므로 한쪽만 읽으면
 * "일관성" 을 이유로 뒤집히기 쉽다.
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
        managerId = ((Number) JsonPath.read(본문(등록), "$.data.id")).longValue();

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
     * 매니저를 지워도 그 계정의 로그인은 <b>200</b> 이다 — 이 Phase 가 내린 판정 그대로다.
     *
     * <p>삭제 <b>전</b>도 함께 두드린다. 뒤쪽만 보면 "기사 로그인은 원래 안 된다" 와 구별되지 않아
     * 삭제가 무엇을 바꿨는지(아무것도 바꾸지 않았다는 것이 답이다) 판정할 수단이 부재해진다.
     *
     * <p>이 단언을 바꾸려면 위 클래스 주석의 근거 셋 중 하나가 무너져야 한다 — 예컨대
     * {@code §8.1} 이 매니저 삭제용 코드를 신설하거나 {@code §5.13} 이 "삭제 시 즉시 권한 회수" 를
     * 요건으로 적는 경우다. <b>단언을 조용히 뒤집지 말고 정본을 먼저 고쳐라.</b>
     */
    @Test
    void 매니저를_삭제해도_그_계정의_로그인은_200_이다() throws Exception {
        로그인한다(DRIVER_LOGIN_ID, NEW_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("active"));

        매니저를_삭제한다();

        로그인한다(DRIVER_LOGIN_ID, NEW_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("active"));
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
                .andExpect(status().isOk());
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
        List<Integer> ids = JsonPath.read(본문(result),
                "$.data.items[?(@.name == '%s')].request_id".formatted(name));
        assertThat(ids).as("관계자 큐에 %s 의 요청이 정확히 1건 떠야 한다", loginId).hasSize(1);
        return ids.get(0).longValue();
    }

    private String 본문(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
