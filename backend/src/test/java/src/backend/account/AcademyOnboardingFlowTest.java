package src.backend.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 3 목표 1 — 메인 관리자 로그인 → 학원 등록 → 관계자 가입 요청 승인 → 관계자 로그인 →
 * 학부모 가입 요청 승인·거절을 <b>한 흐름</b>으로 밟는다.
 *
 * <p>단계마다 시드 값을 새로 집어 오면 흐름이 아니라 독립 호출 6개다 — 그러면 "등록한 학원에 실제로
 * 가입할 수 있는가" 를 아무도 검사하지 않는다. 그래서 앞 단계 응답값({@code academy_id} · 생성된
 * {@code code} · {@code request_id})만을 뒤 단계 입력으로 넘기고, 시드는 메인 관리자 자격 하나만 쓴다.
 *
 * <p>학부모 수락에는 AUTH-11 이 요구하는 연결 대상(학생)이 필요하다. 그 학생을 <b>승인된 관계자가
 * 등록 API(STU-01)로 직접 만든다</b> — Phase 3 시점에는 그 API 가 부재해 raw INSERT 로 심었던
 * 자리이며, Phase 5 가 API 를 만들면서 실제 경로로 바꿨다(목표 10).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AcademyOnboardingFlowTest {

    /** 시드 계정의 비밀번호 — local 프로파일의 Flyway placeholder 가 이 평문의 해시를 심는다. */
    private static final String SEED_PASSWORD = "password";

    private static final String NEW_PASSWORD = "password1234!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * JPA 가 쌓아 둔 변경을 DB 로 내보낸 뒤 raw SQL 로 읽는다.
     *
     * <p>테스트가 트랜잭션을 열고 있어 서비스의 {@code @Transactional} 이 그것에 합류한다 — 메서드가
     * 끝나도 커밋·flush 가 일어나지 않아, 이 호출이 없으면 {@link JdbcTemplate} 이 <b>변경 전</b> 값을
     * 읽는다. 프로덕션에서는 요청마다 트랜잭션이 끝나므로 이 사정이 부재하다.
     */
    private void 반영한다() {
        entityManager.flush();
    }

    @Test
    void 학원_등록부터_학부모_승인과_거절까지_한_흐름으로_완주한다() throws Exception {
        String adminToken = 로그인한다("sysadmin", SEED_PASSWORD, "active");

        MvcResult 등록 = mockMvc.perform(post("/api/v1/admin/academies")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"P3T2흐름학원\", \"region\": \"성남\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        long academyId = ((Number) JsonPath.read(본문(등록), "$.data.academy_id")).longValue();
        String academyCode = JsonPath.read(본문(등록), "$.data.code");

        가입_경로에서_찾을_수_있다(academyCode, academyId);

        가입한다("staff", "p3t2flowstaff", "010-0000-6001", academyId);
        long 관계자_요청 = 관계자_요청_식별자(adminToken, academyCode);

        mockMvc.perform(post("/api/v1/admin/staff-signup-requests/" + 관계자_요청 + "/decide")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accept\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.account_status").value("active"));

        String staffToken = 로그인한다("p3t2flowstaff", NEW_PASSWORD, "active");
        long studentId = 학생을_등록한다(staffToken);

        가입한다("parent", "p3t2flowparent", "010-0000-6002", academyId);
        long 수락할_요청 = 학부모_요청_식별자(staffToken, "p3t2flowparent");

        mockMvc.perform(post("/api/v1/staff/signup-requests/" + 수락할_요청 + "/decide")
                        .header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accept\": true, \"link\": {\"student_ids\": [%d]}}".formatted(studentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.account_status").value("active"));

        가입한다("parent", "p3t2flowreject", "010-0000-6003", academyId);
        long 거절할_요청 = 학부모_요청_식별자(staffToken, "p3t2flowreject");

        mockMvc.perform(post("/api/v1/staff/signup-requests/" + 거절할_요청 + "/decide")
                        .header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accept\": false, \"reject_reason\": \"연락처 확인 불가\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.account_status").value("rejected"));

        assertThat(계정_상태("p3t2flowstaff")).isEqualTo("active");
        assertThat(계정_상태("p3t2flowparent")).isEqualTo("active");
        assertThat(계정_상태("p3t2flowreject")).isEqualTo("rejected");
        반영한다();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM guardian_student gs JOIN guardian g ON g.id = gs.guardian_id
                WHERE g.academy_id = ? AND gs.student_id = ?
                """, Integer.class, academyId, studentId))
                .as("등록한 학원의 학생에 그 학원 학부모가 실제로 이어져야 흐름이 완주한 것이다")
                .isEqualTo(1);
    }

    // ── 도우미 ────────────────────────────────────────────────────────────

    /**
     * 서버가 생성한 학원 코드로 가입용 검색을 부르고, 나온 학원이 방금 등록한 그 학원인지 본다.
     *
     * <p>등록 응답의 {@code code} 를 뒤 단계가 목록 대조(어느 요청이 이 학원 것인가)에만 쓰면 코드
     * 생성기가 <b>실제 가입 경로</b>에 닿는지는 아무도 검사하지 않는다 — 학부모·관계자가 학원을 고르는
     * 유일한 수단이 이 검색이라(API_SPEC §2.1), 코드가 검색에 걸리지 않으면 등록한 학원에 아무도
     * 가입할 수 없다. 식별자까지 대조해야 "같은 이름의 다른 학원이 나온" 경우와 갈린다.
     */
    private void 가입_경로에서_찾을_수_있다(String academyCode, long academyId) throws Exception {
        mockMvc.perform(get("/api/v1/academies/search").param("q", academyCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(String.valueOf(academyId)));
    }

    private String 로그인한다(String loginId, String password, String expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login_id\": \"%s\", \"password\": \"%s\"}".formatted(loginId, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(expectedStatus))
                .andReturn();
        return "Bearer " + JsonPath.read(본문(result), "$.data.access_token");
    }

    private void 가입한다(String role, String loginId, String phone, long academyId) throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role": "%s", "login_id": "%s", "password": "%s", "name": "흐름-%s",
                                 "phone": "%s", "academy_id": "%d"}
                                """.formatted(role, loginId, NEW_PASSWORD, loginId, phone, academyId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.account_status").value("pending"));
    }

    /** 메인 관리자 목록에서 <b>방금 등록한 학원 코드</b>로 요청을 찾는다 — 목록이 전 학원 범위라 코드로 가른다. */
    private long 관계자_요청_식별자(String adminToken, String academyCode) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/admin/staff-signup-requests")
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andReturn();
        List<Integer> ids = JsonPath.read(본문(result),
                "$.data.items[?(@.academy.code == '%s')].request_id".formatted(academyCode));
        assertThat(ids).as("등록한 학원의 관계자 요청이 메인 관리자 큐에 떠야 흐름이 이어진다").hasSize(1);
        return ids.get(0).longValue();
    }

    private long 학부모_요청_식별자(String staffToken, String loginId) throws Exception {
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

    /**
     * 방금 승인된 관계자가 <b>자기 학원의 학생을 실제 등록 경로로</b> 만든다 (STU-01, §5.11).
     *
     * <p>raw INSERT 였다가 바뀐 자리다 — Phase 3 시점에는 학생 등록 API 가 부재했다. SQL 은 <b>API 가
     * 만들어 내지 못하는 상태</b>도 만들 수 있어(학원 범위를 벗어난 학생·필수 값이 빠진 학생), 그
     * 위에서 초록인 흐름은 운영에서 나올 수 없는 데이터를 검사하게 된다. 학생을 등록 API 로 만들면
     * 이 흐름이 "승인된 관계자가 곧바로 학생을 등록할 수 있는가" 까지 함께 밟는다.
     *
     * <p>등록은 멀티파트이고 JSON 은 {@code data} 파트다(§1.1).
     */
    private long 학생을_등록한다(String staffToken) throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/v1/staff/students")
                        .file(new MockMultipartFile("data", "", "application/json",
                                "{\"name\": \"P3T2흐름학생\", \"can_go_alone\": false}"
                                        .getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", staffToken))
                .andExpect(status().isCreated())
                .andReturn();
        return Long.parseLong(JsonPath.read(본문(result), "$.data.student_id"));
    }

    private String 계정_상태(String loginId) {
        반영한다();
        return jdbcTemplate.queryForObject("SELECT status FROM account WHERE login_id = ?", String.class, loginId);
    }

    private String 본문(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
