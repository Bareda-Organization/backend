package src.backend.exception.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static testsupport.auth.TestTokens.토큰;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;
import org.springframework.context.annotation.Import;

import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import testsupport.clock.FixedClock20300401Config;

/**
 * 기사·동승자 단말의 현장 예외 보고 등록 API(§4.13, EXC-02·03).
 *
 * <p>{@code @Transactional} 을 쓴다 — {@code DriverRunControllerTest} 와 같은 근거로, 커맨드
 * 서비스의 {@code @Transactional} 은 기본 전파라 테스트 트랜잭션에 합류하고 끝나면 함께 롤백된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(FixedClock20300401Config.class)
class RunReportControllerTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;



    // ── goal 15 — 등록 성공 ────────────────────────────────────────────────

    @Test
    @DisplayName("goal15 — 도로 통제(rider_id 불필요) 보고는 배치된 기사가 등록하면 201 이고 저장된다")
    void 도로_통제_보고는_배치된_기사가_등록하면_201이다() throws Exception {
        ExceptionReportFixtures fixtures = ExceptionReportFixtures.of(applicationContext);
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        OffsetDateTime departTime = OffsetDateTime.now(clock);
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", OffsetDateTime.now(clock));

        mockMvc.perform(post("/api/v1/runs/" + runId + "/reports")
                .header("Authorization", 토큰(tokenProvider, driverAccountId, academyId, Role.DRIVER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"road_block\",\"memo\":\"도로 공사로 우회 중\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.report_id").exists())
                .andExpect(jsonPath("$.data.reported_at").exists());

        assertThat(보고_개수(runId)).isEqualTo(1);
        assertThat(보고_타입(runId)).isEqualTo("road_block");
        assertThat(보고_라이더(runId)).as("보호자 부재가 아니면 run_rider_id 가 비어 있어야 한다").isNull();
    }

    @Test
    @DisplayName("goal15 — 보호자 부재 보고는 유효한 rider_id 와 함께 배치된 동승자가 등록하면 201 이고 run_rider_id 가 저장된다")
    void 보호자_부재_보고는_유효한_rider_id로_동승자가_등록하면_201이다() throws Exception {
        ExceptionReportFixtures fixtures = ExceptionReportFixtures.of(applicationContext);
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long stopId = fixtures.stop(academyId, "37.560000", "126.970000");
        OffsetDateTime departTime = OffsetDateTime.now(clock);
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        long escortAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.ESCORT, "동승자", OffsetDateTime.now(clock));
        long studentId = fixtures.student(academyId, "학생1");
        long runRiderId = fixtures.rider(runId, studentId, stopId);

        mockMvc.perform(post("/api/v1/runs/" + runId + "/reports")
                .header("Authorization", 토큰(tokenProvider, escortAccountId, academyId, Role.ESCORT))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"guardian_absent\",\"memo\":\"보호자가 정류장에 없음\",\"rider_id\":" + runRiderId + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.report_id").exists());

        assertThat(보고_타입(runId)).isEqualTo("guardian_absent");
        assertThat(보고_라이더(runId)).isEqualTo(runRiderId);
    }

    // ── Ruling 894 ⑧ — 오프라인 큐 재전송 키 ───────────────────────────

    @Test
    @DisplayName("R56 K4 — 같은 client_key 를 두 번 보내면 보고 1개·관계자 알림 1건이고 두 번째는 200 으로 같은 report_id 를 돌려준다")
    void 같은_client_key_재전송은_보고를_늘리지_않고_200이다() throws Exception {
        ExceptionReportFixtures fixtures = ExceptionReportFixtures.of(applicationContext);
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long staffAccountId = fixtures.staffAccount(academyId, "관계자1");
        OffsetDateTime departTime = OffsetDateTime.now(clock);
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", OffsetDateTime.now(clock));
        String body = "{\"type\":\"road_block\",\"memo\":\"도로 공사로 우회 중\",\"client_key\":\"" + UUID.randomUUID() + "\"}";

        MvcResult first = mockMvc.perform(post("/api/v1/runs/" + runId + "/reports")
                .header("Authorization", 토큰(tokenProvider, driverAccountId, academyId, Role.DRIVER))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult second = mockMvc.perform(post("/api/v1/runs/" + runId + "/reports")
                .header("Authorization", 토큰(tokenProvider, driverAccountId, academyId, Role.DRIVER))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed").doesNotExist()).andReturn();

        assertThat(JsonPath.<Object>read(second.getResponse().getContentAsString(StandardCharsets.UTF_8),
                "$.data.report_id")).isEqualTo(JsonPath.<Object>read(
                        first.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.report_id"));
        assertThat(보고_개수(runId)).as("재전송이 보고를 두 번 만들면 안 된다").isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_log WHERE recipient_account_id = ? AND type = 'exception_reported'",
                Integer.class, staffAccountId)).as("관계자 통지도 한 번이다").isEqualTo(1);
    }

    @Test
    @DisplayName("R56 K4 — 같은 client_key 로 다른 내용(종류)을 보내면 422 VALIDATION_FAILED 이고 새 보고가 생기지 않는다")
    void 같은_client_key_다른_내용은_422다() throws Exception {
        ExceptionReportFixtures fixtures = ExceptionReportFixtures.of(applicationContext);
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        OffsetDateTime departTime = OffsetDateTime.now(clock);
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", OffsetDateTime.now(clock));
        String key = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/v1/runs/" + runId + "/reports")
                .header("Authorization", 토큰(tokenProvider, driverAccountId, academyId, Role.DRIVER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"road_block\",\"memo\":\"도로 공사\",\"client_key\":\"" + key + "\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/runs/" + runId + "/reports")
                .header("Authorization", 토큰(tokenProvider, driverAccountId, academyId, Role.DRIVER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"vehicle_issue\",\"memo\":\"고장\",\"client_key\":\"" + key + "\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertThat(보고_개수(runId)).isEqualTo(1);
    }

    @Test
    @DisplayName("R56 K4 — client_key 없이 같은 요청을 두 번 보내면 지금처럼 두 번 다 201 이고 보고가 2개다")
    void client_key_가_없으면_지금과_같다() throws Exception {
        ExceptionReportFixtures fixtures = ExceptionReportFixtures.of(applicationContext);
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        OffsetDateTime departTime = OffsetDateTime.now(clock);
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", OffsetDateTime.now(clock));

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/v1/runs/" + runId + "/reports")
                    .header("Authorization", 토큰(tokenProvider, driverAccountId, academyId, Role.DRIVER))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"type\":\"road_block\",\"memo\":\"도로 공사\"}"))
                    .andExpect(status().isCreated());
        }

        assertThat(보고_개수(runId)).isEqualTo(2);
    }

    // ── Ruling 894 ⑪-c · guardian_absent 를 서버가 거르지 않는다 ─────────────────

    /**
     * 판정 894 ⑪ — 서버는 {@code guardian_absent} 보고의 대상을 "혼자 귀가 불가 학생" 으로 거르지 않는다. 현장의 보호자 부재는 안전 보고라
     * 학생 정보({@code can_go_alone})가 틀려 있어도 관계자에게 가야 하므로, 혼자 귀가 가능으로 등록된 학생 · 등원 회차의 보고도 접수된다.
     * 이 시험을 깨뜨리는 변경(서버가 그 조건으로 보고를 되돌림)은 새 판정이 먼저 있어야 한다.
     */
    @Test
    @DisplayName("Ruling 894 ⑪ — can_go_alone=true 학생·등원 회차의 guardian_absent 보고도 접수되어 201 이다(안전 보고를 되돌리지 않는다)")
    void 혼자_귀가_가능_학생의_보호자_부재_보고도_접수된다() throws Exception {
        ExceptionReportFixtures fixtures = ExceptionReportFixtures.of(applicationContext);
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long stopId = fixtures.stop(academyId, "37.560000", "126.970000");
        OffsetDateTime departTime = OffsetDateTime.now(clock);
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        long escortAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.ESCORT, "동승자", OffsetDateTime.now(clock));
        long studentId = fixtures.student(academyId, "혼자귀가학생");
        jdbcTemplate.update("UPDATE student SET can_go_alone = true WHERE id = ?", studentId);
        long runRiderId = fixtures.rider(runId, studentId, stopId);

        mockMvc.perform(post("/api/v1/runs/" + runId + "/reports")
                .header("Authorization", 토큰(tokenProvider, escortAccountId, academyId, Role.ESCORT))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"guardian_absent\",\"memo\":\"보호자가 없음\",\"rider_id\":" + runRiderId + "}"))
                .andExpect(status().isCreated());

        assertThat(보고_개수(runId)).isEqualTo(1);
        assertThat(보고_라이더(runId)).isEqualTo(runRiderId);
    }

    // ── goal 15 — 관계자 즉시 통지 ─────────────────────────────────────────

    /**
     * §4.13 "관계자에게 즉시 통지" — 접수 성공 직후 그 학원 관계자(재직 스태프)에게
     * {@code exception_reported} 알림이 적재되는지 확인한다({@code BoardingIntentControllerTest} 의
     * {@code intent_changed}/{@code approval_requested} 적재 검사와 같은 형태). {@code notification_log.run_id}
     * 는 이 파이프라인이 채우지 않으므로 dedup_key 접두 매칭으로 찾는다.
     */
    @Test
    @DisplayName("goal15 — 보고 접수에 성공하면 그 학원 관계자에게 exception_reported 알림이 적재된다")
    void 보고_접수에_성공하면_관계자에게_알림이_적재된다() throws Exception {
        ExceptionReportFixtures fixtures = ExceptionReportFixtures.of(applicationContext);
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long staffAccountId = fixtures.staffAccount(academyId, "관계자1");
        OffsetDateTime departTime = OffsetDateTime.now(clock);
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", OffsetDateTime.now(clock));

        mockMvc.perform(post("/api/v1/runs/" + runId + "/reports")
                .header("Authorization", 토큰(tokenProvider, driverAccountId, academyId, Role.DRIVER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"road_block\",\"memo\":\"도로 공사로 우회 중\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.report_id").exists());

        long reportId = jdbcTemplate.queryForObject(
                "SELECT id FROM exception_report WHERE run_id = ? ORDER BY id DESC LIMIT 1", Long.class, runId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_log WHERE recipient_account_id = ? AND type = 'exception_reported' AND dedup_key LIKE ?",
                Integer.class, staffAccountId, "exception_reported:" + reportId + ":%")).isEqualTo(1);
    }

    // ── goal 15 — 422 vs 404 구분 ──────────────────────────────────────────

    @Test
    @DisplayName("goal15 급소 — type=guardian_absent 인데 rider_id 가 없으면 422 VALIDATION_FAILED 이고 저장되지 않는다")
    void 보호자_부재_보고에_rider_id가_없으면_422다() throws Exception {
        ExceptionReportFixtures fixtures = ExceptionReportFixtures.of(applicationContext);
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        OffsetDateTime departTime = OffsetDateTime.now(clock);
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", OffsetDateTime.now(clock));

        mockMvc.perform(post("/api/v1/runs/" + runId + "/reports")
                .header("Authorization", 토큰(tokenProvider, driverAccountId, academyId, Role.DRIVER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"guardian_absent\",\"memo\":\"보호자가 없음\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertThat(보고_개수(runId)).as("rider_id 누락이면 저장되면 안 된다").isEqualTo(0);
    }

    @Test
    @DisplayName("goal15 급소 — type=guardian_absent 인데 rider_id 가 이 회차의 탑승자가 아니면 404 RIDER_NOT_FOUND 이고 저장되지 않는다")
    void 보호자_부재_보고에_rider_id가_유효하지_않으면_404다() throws Exception {
        ExceptionReportFixtures fixtures = ExceptionReportFixtures.of(applicationContext);
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long stopId = fixtures.stop(academyId, "37.560000", "126.970000");
        OffsetDateTime departTime = OffsetDateTime.now(clock);
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", OffsetDateTime.now(clock));

        // 다른 회차의 라이더 — id 는 존재하지만 이 회차 소속이 아니다.
        long otherRunId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY,
                departTime.plusHours(1), departTime.minusMinutes(30));
        long studentId = fixtures.student(academyId, "학생1");
        long otherRunRiderId = fixtures.rider(otherRunId, studentId, stopId);

        mockMvc.perform(post("/api/v1/runs/" + runId + "/reports")
                .header("Authorization", 토큰(tokenProvider, driverAccountId, academyId, Role.DRIVER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"guardian_absent\",\"memo\":\"보호자가 없음\",\"rider_id\":" + otherRunRiderId + "}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RIDER_NOT_FOUND"));

        assertThat(보고_개수(runId)).as("rider_id 가 이 회차 소속이 아니면 저장되면 안 된다").isEqualTo(0);
    }

    // ── goal 15 — 회차·인가 ────────────────────────────────────────────────

    @Test
    @DisplayName("goal15 — 배치는 통과해도 토큰이 주장하는 academy_id 가 그 회차의 실제 학원과 다르면 404 RUN_NOT_FOUND 이고 저장되지 않는다"
            + "(RunAssignmentAccess#assertAssignedDriverOrEscort 는 학원으로 좁히지 않는 조회라 이 뒤 단계의 회차 조회가 학원 경계를 지킨다)")
    void 배치는_맞아도_토큰_academy가_다르면_RUN_NOT_FOUND다() throws Exception {
        ExceptionReportFixtures fixtures = ExceptionReportFixtures.of(applicationContext);
        long realAcademyId = fixtures.academy();
        long otherAcademyId = fixtures.academy();
        long busId = fixtures.bus(realAcademyId);
        OffsetDateTime departTime = OffsetDateTime.now(clock);
        long runId = fixtures.confirmedRun(realAcademyId, busId, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        long driverAccountId = fixtures.assignedManager(realAcademyId, runId, ManagerRole.DRIVER, "기사", OffsetDateTime.now(clock));

        // 배치 조회는 통과하지만(학원으로 좁히지 않으므로), 토큰이 주장하는 academy_id 가 실제와 다르면
        // 회차 조회(findByIdAndAcademyId)가 걸러야 한다 — 그렇지 않으면 남의 학원 회차가 새는 것과 같다.
        mockMvc.perform(post("/api/v1/runs/" + runId + "/reports")
                .header("Authorization", 토큰(tokenProvider, driverAccountId, otherAcademyId, Role.DRIVER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"road_block\",\"memo\":\"도로 공사\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_FOUND"));

        assertThat(보고_개수(runId)).as("학원 불일치면 저장되면 안 된다").isEqualTo(0);
    }

    @Test
    @DisplayName("goal15 FORBIDDEN — 같은 학원의 기사이지만 이 회차에 배치되지 않으면 403 FORBIDDEN 이고 저장되지 않는다")
    void 배치되지_않은_같은_학원_기사는_FORBIDDEN_이다() throws Exception {
        ExceptionReportFixtures fixtures = ExceptionReportFixtures.of(applicationContext);
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        OffsetDateTime departTime = OffsetDateTime.now(clock);
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "배치된기사", OffsetDateTime.now(clock));
        long unassignedDriverAccountId = fixtures.unassignedManager(academyId, ManagerRole.DRIVER, "미배치기사");

        mockMvc.perform(post("/api/v1/runs/" + runId + "/reports")
                .header("Authorization", 토큰(tokenProvider, unassignedDriverAccountId, academyId, Role.DRIVER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"road_block\",\"memo\":\"도로 공사\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertThat(보고_개수(runId)).as("배치되지 않은 호출은 저장되면 안 된다").isEqualTo(0);
    }

    @Test
    @DisplayName("goal15 FORBIDDEN — 이 회차에 배치된 기사라도 토큰의 역할이 기사·동승자가 아니면 403 FORBIDDEN 이다")
    void 토큰_역할이_기사_동승자가_아니면_FORBIDDEN_이다() throws Exception {
        ExceptionReportFixtures fixtures = ExceptionReportFixtures.of(applicationContext);
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        OffsetDateTime departTime = OffsetDateTime.now(clock);
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", OffsetDateTime.now(clock));

        mockMvc.perform(post("/api/v1/runs/" + runId + "/reports")
                .header("Authorization", 토큰(tokenProvider, driverAccountId, academyId, Role.STAFF))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"road_block\",\"memo\":\"도로 공사\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertThat(보고_개수(runId)).as("역할 판정에서 거절됐으면 저장되면 안 된다").isEqualTo(0);
    }

    // ── 픽스처 · 호출 도우미 ──────────────────────────────────────────────

    private Integer 보고_개수(long runId) {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM exception_report WHERE run_id = ?",
                Integer.class, runId);
        return count == null ? 0 : count;
    }

    private String 보고_타입(long runId) {
        return jdbcTemplate.queryForObject(
                "SELECT type FROM exception_report WHERE run_id = ? ORDER BY id DESC LIMIT 1", String.class, runId);
    }

    private Long 보고_라이더(long runId) {
        return jdbcTemplate.queryForObject(
                "SELECT run_rider_id FROM exception_report WHERE run_id = ? ORDER BY id DESC LIMIT 1", Long.class,
                runId);
    }
}
