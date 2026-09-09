package src.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;

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

import src.backend.global.common.SeedFixtures;
import src.backend.schedule.command.RunGenerationService;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Phase 5 목표 1 — 관계자 로그인 → 학생 등록 → 차량 등록 → 매니저 등록 → 스케줄 등록 →
 * <b>학부모</b> 로그인 → 자녀 연결(요청 → 코드 → 입력) → 요일별 주소 설정 → 회차 생성 배치 →
 * 오늘 회차 조회 → 매니저 배치를 <b>한 흐름</b>으로 밟는다(순서는 Ruling 154).
 *
 * <p><b>앞 단계 응답값만이 뒤 단계 입력이다.</b> 단계마다 시드 값을 새로 집어 오면 흐름이 아니라
 * 독립 호출 11개이고, 그러면 "등록한 학생에게 주소가 붙는가 · 만든 스케줄에서 회차가 나오는가" 를
 * 아무도 검사하지 않는다. 시드에서 가져오는 것은 <b>출발 자격 둘</b>({@code staffA}·{@code parentA1})
 * 뿐이며, 그 밖의 식별자는 전부 앞 단계 응답에서 받는다.
 *
 * <p><b>자녀 연결은 역할이 셋이다</b> — 학부모가 요청하고, <b>학생</b>이 코드를 발급하고, 학부모가
 * 그 코드를 넣는다. 토큰을 바꿔 가며 밟지 않으면 "서버가 코드를 대조한다"(§3.4)는 전제가 검사되지
 * 않는다. 그래서 등록한 학생에게 계정을 붙이는 가입 승인(AUTH-11, §5.2)까지 흐름 안에 들어온다 —
 * {@code POST /me/students/link-requests} 가 {@code student_login_id} 를 받으므로(§3.2) 계정 없는
 * 학생 레코드로는 이 구간이 성립하지 않는다.
 *
 * <p><b>날짜를 고정하지 않는다</b>(Ruling 176). 시드 스케줄의 요일과 시드 회차의 날짜가
 * {@code extract(dow from now())}·{@code CURRENT_DATE} 라 <b>DB 를 만든 날</b>에 정해지는데, 여기에
 * 고정 시계를 겹치면 두 시계가 다른 날을 가리키는 순간 통과 여부가 DB 프로비저닝 날짜에 매인다.
 * 이 흐름은 스케줄의 요일도, 배치에 넘기는 날짜도, "오늘 회차 조회" 가 해석하는 오늘도 <b>같은
 * 시스템 시계</b>에서 뽑는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OperationSetupFlowTest {

    /** 시드 계정의 비밀번호 — local 프로파일의 Flyway placeholder 가 이 평문의 해시를 심는다. */
    private static final String SEED_PASSWORD = "password";

    private static final String NEW_PASSWORD = "password1234!";

    /** 시드 학원 A — {@code staffA}·{@code parentA1} 이 함께 속한 학원이다. */
    private static final long ACADEMY_A_ID = Long.parseLong(SeedFixtures.ACADEMY_A_ID);

    /** 이 흐름이 만드는 학생의 로그인 아이디 — 연결 요청이 이 값으로 학생을 지목한다(§3.2). */
    private static final String STUDENT_LOGIN_ID = "p5t7flowstudent";

    /**
     * 이 흐름이 세우는 회차의 출발 시각 — 시드 스케줄 넷(08:00·08:10·16:00·16:10)과 겹치지 않는다.
     *
     * <p>겹치면 {@code uk_run_bus_date_direction_depart} 가 걸리는 것이 아니라(차량이 다르다) 조회
     * 결과에 남의 회차가 섞여 "내가 만든 스케줄에서 나온 회차인가" 를 가릴 수 없게 된다.
     */
    private static final String DEPART_TIME = "05:07";

    /** 번지가 붙어 결정론적 스텁이 좌표로 옮길 수 있는 주소({@code StubGeocodingClient}). */
    private static final String ADDRESS = "서울시 테스트로 100";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RunGenerationService runGenerationService;

    /**
     * 시각 계산의 기준 시간대를 프로덕션과 같은 출처에서 받는다 — {@code ZoneId} 를 테스트에 다시
     * 적으면 이 흐름만 시스템 시간대를 따라 남아, 자정 부근에서 "오늘" 이 서로 다른 날이 된다.
     */
    @Autowired
    private Clock clock;

    /** 변경 감지가 만든 INSERT·UPDATE 는 커밋 시점에야 나간다 — {@link JdbcTemplate} 로 읽기 전에 밀어낸다. */
    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void 학생_차량_매니저_스케줄_등록부터_회차_배치까지_한_흐름으로_완주한다() throws Exception {
        LocalDate 오늘 = LocalDate.now(clock);
        String 오늘_요일 = 요일명(오늘);

        // ① 관계자 로그인 — 이 흐름이 시드에서 가져오는 자격 둘 중 하나다.
        String staffToken = 로그인한다(SeedFixtures.STAFF_A_LOGIN_ID, SEED_PASSWORD, "active");

        // ② 학생 등록 (STU-01) — 뒤의 가입 승인·연결·주소가 전부 이 식별자를 받는다.
        long studentId = 학생을_등록한다(staffToken);

        // ②' 등록한 학생에 계정을 붙인다 (AUTH-11 · §5.2) — 연결 3단계의 학생 역할이 여기서 생긴다.
        가입한다("student", STUDENT_LOGIN_ID, "010-3000-7001");
        long 학생_요청 = 학부모_학생_요청_식별자(staffToken, STUDENT_LOGIN_ID);
        mockMvc.perform(post("/api/v1/staff/signup-requests/" + 학생_요청 + "/decide")
                        .header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accept\": true, \"link\": {\"student_ids\": [%d]}}".formatted(studentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.account_status").value("active"));

        // ③ 차량 등록 (BUS-02) — 스케줄이 이 차량을 쓰고, 회차가 그것을 물려받는다.
        long busId = 차량을_등록한다(staffToken);

        // ④ 매니저 등록 (MGR-02) — 마지막 단계의 배치 대상이다.
        long managerId = 매니저를_등록한다(staffToken, 오늘_요일);

        // ⑤ 스케줄 등록 (SCH-01) — 오늘 요일로 세워야 오늘의 배치가 이것을 집는다.
        long scheduleId = 스케줄을_등록한다(staffToken, busId, 오늘_요일);

        // ⑥ 학부모 로그인 — 시드에서 가져오는 두 번째 자격이다.
        String parentToken = 로그인한다(SeedFixtures.PARENT_A1_LOGIN_ID, SEED_PASSWORD, "active");

        // ⑦ 자녀 연결 3단계 (P-02 · S-05) — 학부모 요청 → 학생 코드 발급 → 학부모 코드 입력.
        mockMvc.perform(post("/api/v1/me/students/link-requests")
                        .header("Authorization", parentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"student_login_id\": \"%s\"}".formatted(STUDENT_LOGIN_ID)))
                .andExpect(status().isCreated());

        String studentToken = 로그인한다(STUDENT_LOGIN_ID, NEW_PASSWORD, "active");
        MvcResult 코드_발급 = mockMvc.perform(post("/api/v1/me/link-code")
                        .header("Authorization", studentToken))
                .andExpect(status().isCreated())
                .andReturn();
        String code = JsonPath.read(본문(코드_발급), "$.data.code");

        mockMvc.perform(post("/api/v1/me/students/link")
                        .header("Authorization", parentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"%s\"}".formatted(code)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.student_id").value(String.valueOf(studentId)));

        // ⑧ 요일별 주소 설정 (P-05 · STU-05·06) — 연결된 학부모만 닿을 수 있는 경로다(목표 8).
        mockMvc.perform(patch("/api/v1/students/" + studentId + "/weekly-address")
                        .header("Authorization", parentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entries": [{"weekday": "%s", "direction": "to_academy", "address": "%s"}]}
                                """.formatted(오늘_요일, ADDRESS)))
                .andExpect(status().isOk());
        승하차지가_붙었는지_본다(studentId, 오늘_요일);

        // ⑨ 회차 생성 배치 (SCH-02) — 엔드포인트가 아니라 DailyRunGenerator 가 부르는 그 경로다.
        runGenerationService.generate(오늘);

        // ⑩ 오늘 회차 조회 (SCH-02) — 날짜를 주지 않는다. 배치가 넘긴 날과 조회가 해석하는 오늘이
        //    갈리면 여기서 드러난다.
        MvcResult 회차_목록 = mockMvc.perform(get("/api/v1/staff/runs").header("Authorization", staffToken))
                .andExpect(status().isOk())
                .andReturn();
        List<Map<String, Object>> 내_회차 = JsonPath.read(본문(회차_목록),
                "$.data[?(@.schedule_id == %d)]".formatted(scheduleId));
        assertThat(내_회차)
                .as("등록한 스케줄에서 오늘의 회차가 나와야 ⑤와 ⑨가 이어진 것이다")
                .hasSize(1);
        long runId = ((Number) 내_회차.get(0).get("id")).longValue();
        assertThat(((Number) 내_회차.get(0).get("bus_id")).longValue())
                .as("회차가 ③에서 등록한 차량을 물려받아야 스케줄이 실제로 그 차량을 쓴 것이다")
                .isEqualTo(busId);

        // ⑪ 매니저 배치 (MGR-05) — 회차가 있어야 assignment.run_id(FK NN)를 채울 수 있다(Ruling 154 ②).
        mockMvc.perform(patch("/api/v1/staff/runs/" + runId + "/assignment")
                        .header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"driver_manager_id\": %d}".formatted(managerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assignments[0].manager_id").value(managerId));

        흐름이_DB_에_남긴_것을_본다(studentId, runId, managerId);
    }

    // ── 단계 ──────────────────────────────────────────────────────────────

    /** {@code POST /staff/students} 는 멀티파트이고 JSON 은 {@code data} 파트다(§1.1 · P5-T6). */
    private long 학생을_등록한다(String staffToken) throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/v1/staff/students")
                        .file(데이터_파트("{\"name\": \"P5T7흐름학생\", \"can_go_alone\": false}"))
                        .header("Authorization", staffToken))
                .andExpect(status().isCreated())
                .andReturn();
        return Long.parseLong(JsonPath.read(본문(result), "$.data.student_id"));
    }

    private long 차량을_등록한다(String staffToken) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/staff/buses")
                        .header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bus_no": "P5T7흐름호차", "plate_no": "77가7777", "capacity": 20}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(본문(result), "$.data.id")).longValue();
    }

    /** 근무 시간은 오늘 요일의 출발 시각을 덮는 구간으로 준다 — MGR-06 경고 없이 배치되는 쪽이다. */
    private long 매니저를_등록한다(String staffToken, String 오늘_요일) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/staff/managers")
                        .header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "P5T7흐름기사", "phone": "010-7777-7001", "role": "driver",
                                 "work_hours": {"%s": [{"start": "05:00", "end": "06:00"}]}}
                                """.formatted(오늘_요일)))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(본문(result), "$.data.id")).longValue();
    }

    private long 스케줄을_등록한다(String staffToken, long busId, String 오늘_요일) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/staff/schedules")
                        .header("Authorization", staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bus_id": %d, "weekday": "%s", "direction": "to_academy",
                                 "depart_time": "%s", "origin_name": "P5T7흐름집결지",
                                 "destination_name": "바래다학원 A"}
                                """.formatted(busId, 오늘_요일, DEPART_TIME)))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(본문(result), "$.data.id")).longValue();
    }

    // ── 단언 ──────────────────────────────────────────────────────────────

    /**
     * 주소가 저장되기만 한 것이 아니라 <b>승하차지에 매칭됐는지</b>까지 본다(STU-05).
     *
     * <p>{@code stop_id} 단언이 없으면 주소만 저장하고 매칭을 건너뛴 구현이 통과하는데, 그 상태로는
     * Phase 6 노선 계산의 입력이 비어 있다 — 실패가 이 Phase 가 아니라 다음 Phase 에서 드러난다.
     */
    private void 승하차지가_붙었는지_본다(long studentId, String 오늘_요일) {
        entityManager.flush();
        Map<String, Object> 주소 = jdbcTemplate.queryForMap(
                "SELECT stop_id, verified FROM weekly_address WHERE student_id = ? AND weekday = ? "
                        + "AND direction = 'to_academy'", studentId, 오늘_요일);
        assertThat(주소.get("verified")).as("검증을 거친 주소만 저장된다(§3.7)").isEqualTo(true);
        assertThat(주소.get("stop_id")).as("주소만 저장하고 승하차지 매칭을 건너뛰면 여기서 NULL 이다").isNotNull();

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM stop WHERE id = ? AND academy_id = ?",
                Integer.class, 주소.get("stop_id"), ACADEMY_A_ID))
                .as("매칭된 승하차지가 그 학원의 stop 행으로 실재해야 한다")
                .isEqualTo(1);
    }

    /**
     * 흐름이 남긴 세 관계를 DB 에서 되읽는다 — 응답만 보면 "쓰지 않고 되돌려준 구현" 과 갈리지 않는다.
     */
    private void 흐름이_DB_에_남긴_것을_본다(long studentId, long runId, long managerId) {
        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM guardian_student gs JOIN guardian g ON g.id = gs.guardian_id
                WHERE g.account_id = (SELECT id FROM account WHERE login_id = ?)
                  AND gs.student_id = ? AND gs.unlinked_at IS NULL
                """, Integer.class, SeedFixtures.PARENT_A1_LOGIN_ID, studentId))
                .as("코드 입력이 실제로 guardian_student 를 만들어야 연결 3단계가 완주한 것이다")
                .isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM assignment WHERE run_id = ? AND manager_id = ? AND role = 'driver'",
                Integer.class, runId, managerId))
                .as("배치 응답이 200 이어도 assignment 행이 없으면 아무것도 배치되지 않은 것이다")
                .isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM student WHERE id = ? AND account_id = "
                        + "(SELECT id FROM account WHERE login_id = ?)",
                Integer.class, studentId, STUDENT_LOGIN_ID))
                .as("가입 승인이 등록한 학생 레코드에 계정을 붙여야 연결 3단계의 학생 역할이 성립한다(AUTH-11)")
                .isEqualTo(1);
    }

    // ── 도우미 ────────────────────────────────────────────────────────────

    private String 로그인한다(String loginId, String password, String expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login_id\": \"%s\", \"password\": \"%s\"}".formatted(loginId, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(expectedStatus))
                .andReturn();
        return "Bearer " + JsonPath.read(본문(result), "$.data.access_token");
    }

    private void 가입한다(String role, String loginId, String phone) throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role": "%s", "login_id": "%s", "password": "%s", "name": "흐름-%s",
                                 "phone": "%s", "academy_id": "%d"}
                                """.formatted(role, loginId, NEW_PASSWORD, loginId, phone, ACADEMY_A_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.account_status").value("pending"));
    }

    /** 관계자 승인 큐에서 그 로그인 아이디의 요청을 찾는다 — 큐가 학원 범위라 이름으로 가른다. */
    private long 학부모_학생_요청_식별자(String staffToken, String loginId) throws Exception {
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

    private MockMultipartFile 데이터_파트(String json) {
        return new MockMultipartFile("data", "", "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    /** {@code schedule.weekday} 의 값 공간({@code mon}~{@code sun}) 표기. */
    private static String 요일명(LocalDate date) {
        return date.getDayOfWeek().name().substring(0, 3).toLowerCase(Locale.ROOT);
    }

    private String 본문(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
