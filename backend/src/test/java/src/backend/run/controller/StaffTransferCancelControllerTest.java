package src.backend.run.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.OffsetDateTime;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.repository.AcademyRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.command.RunConfirmationFixtures;
import src.backend.run.entity.RunForcedAddition;
import src.backend.run.entity.RunStatus;
import src.backend.run.repository.RunForcedAdditionRepository;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;
import testsupport.clock.FixedClock20260827Config;

/**
 * §5.8.1 {@code DELETE /staff/transfers/{transferId}}(이동 대기 취소, Ruling 369) — R36-BE 목표 1~4·6.
 *
 * <p>이동은 {@code StaffStudentTransferControllerTest} 와 같이 실제 §5.8 호출로 만든다 — 취소가 지우는
 * 행이 등록 경로가 실제로 쓰는 행이어야 하기 때문이다. 명단 소속은 강제 추가 1건으로 만든다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(FixedClock20260827Config.class)
class StaffTransferCancelControllerTest {

    private static final long STAFF_ACCOUNT_ID = 9101L;

    private static final String CLIENT_IP = "203.0.113.24";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private Clock clock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private BusRepository busRepository;

    @Autowired
    private RouteRepository routeRepository;

    @Autowired
    private RouteStopRepository routeStopRepository;

    @Autowired
    private StopRepository stopRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private WeeklyAddressRepository weeklyAddressRepository;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private RunForcedAdditionRepository runForcedAdditionRepository;

    /** 학원 1곳에 회차 두 개·정차지·학생 1명·이동 대기 1건을 갖춘 장면. */
    private record Scene(long academyId, long fromRunId, long toRunId, long stopId, long studentId, long transferId) {
    }

    // ── 목표 1 · 6 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("목표 1 — staged · 두 회차 idle 이면 204, 행이 지워지고 감사 1행(data_access·delete)이 남는다")
    void 이동_대기를_취소하면_204_이고_행이_지워지며_감사가_남는다() throws Exception {
        Scene scene = 이동_대기_장면();

        취소한다(scene.transferId(), scene.academyId()).andExpect(status().isNoContent());

        assertThat(이동_행_수(scene.transferId())).as("이동 행").isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log WHERE category = 'data_access' "
                + "AND action = 'delete' AND target_type = 'run_transfer' AND target_id = ?", Long.class,
                scene.transferId())).as("감사 기록").isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("SELECT host(ip) FROM audit_log WHERE target_type = 'run_transfer' "
                + "AND target_id = ?", String.class, scene.transferId())).as("감사 기록의 접속 IP").isEqualTo(CLIENT_IP);
    }

    @Test
    @DisplayName("목표 6 — 취소 뒤 그 학생이 출발 회차 예정 명단에 다시 보이고 도착 회차에서 빠지며 다시 이동 등록할 수 있다")
    void 취소하면_학생이_출발_명단으로_돌아오고_다시_이동_등록할_수_있다() throws Exception {
        Scene scene = 이동_대기_장면();
        assertThat(예정_명단_학생_id(scene.fromRunId(), scene.academyId())).doesNotContain(scene.studentId());
        assertThat(예정_명단_학생_id(scene.toRunId(), scene.academyId())).contains(scene.studentId());

        취소한다(scene.transferId(), scene.academyId()).andExpect(status().isNoContent());

        assertThat(예정_명단_학생_id(scene.fromRunId(), scene.academyId())).contains(scene.studentId());
        assertThat(예정_명단_학생_id(scene.toRunId(), scene.academyId())).doesNotContain(scene.studentId());
        이동_등록한다(scene).andExpect(status().isCreated());
    }

    // ── 목표 2 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("목표 2 — 없는 이동은 404 TRANSFER_NOT_FOUND")
    void 없는_이동은_404_이다() throws Exception {
        Scene scene = 이동_대기_장면();

        취소한다(scene.transferId() + 100_000, scene.academyId()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TRANSFER_NOT_FOUND"));
    }

    @Test
    @DisplayName("목표 2 — 타 학원 관계자가 취소하면 404 TRANSFER_NOT_FOUND 이고 행은 남는다(존재 비노출)")
    void 타_학원_이동은_404_이고_행이_남는다() throws Exception {
        Scene scene = 이동_대기_장면();
        long otherAcademyId = new RunConfirmationFixtures(academyRepository, busRepository, routeRepository,
                routeStopRepository, stopRepository, studentRepository, weeklyAddressRepository, runRepository)
                .academyWithCoordinates();

        취소한다(scene.transferId(), otherAcademyId).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TRANSFER_NOT_FOUND"));

        assertThat(이동_행_수(scene.transferId())).isEqualTo(1L);
    }

    // ── 목표 3 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("목표 3 — 도착 회차만 confirmed 여도 403 CHANGE_WINDOW_CLOSED 이고 행이 남는다")
    void 도착_회차만_확정됐어도_403_이다() throws Exception {
        Scene scene = 이동_대기_장면();
        회차_상태를_바꾼다(scene.toRunId(), RunStatus.CONFIRMED);

        취소한다(scene.transferId(), scene.academyId()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CHANGE_WINDOW_CLOSED"));

        assertThat(이동_행_수(scene.transferId())).isEqualTo(1L);
    }

    @Test
    @DisplayName("목표 3 — 출발 회차만 confirmed 여도 403 CHANGE_WINDOW_CLOSED 이고 행이 남는다")
    void 출발_회차만_확정됐어도_403_이다() throws Exception {
        Scene scene = 이동_대기_장면();
        회차_상태를_바꾼다(scene.fromRunId(), RunStatus.CONFIRMED);

        취소한다(scene.transferId(), scene.academyId()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CHANGE_WINDOW_CLOSED"));

        assertThat(이동_행_수(scene.transferId())).isEqualTo(1L);
    }

    @Test
    @DisplayName("목표 3 — 이미 applied 인 이동은 403 CHANGE_WINDOW_CLOSED 이고 행이 남는다")
    void 이미_반영된_이동은_403_이다() throws Exception {
        Scene scene = 이동_대기_장면();
        동기화한다();
        jdbcTemplate.update("UPDATE run_transfer SET status = 'applied', applied_at = now() WHERE id = ?",
                scene.transferId());

        취소한다(scene.transferId(), scene.academyId()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CHANGE_WINDOW_CLOSED"));

        assertThat(이동_행_수(scene.transferId())).isEqualTo(1L);
    }

    // ── 목표 4 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("목표 4 — 학부모·기사 토큰은 403 이고 행이 남는다")
    void 학부모와_기사는_403_이다() throws Exception {
        Scene scene = 이동_대기_장면();

        for (Role role : new Role[] {Role.PARENT, Role.DRIVER}) {
            mockMvc.perform(delete("/api/v1/staff/transfers/" + scene.transferId())
                    .header("Authorization", "Bearer " + tokenProvider.createAccessToken(STAFF_ACCOUNT_ID,
                            scene.academyId(), role, AccountStatus.ACTIVE)))
                    .andExpect(status().isForbidden());
        }

        assertThat(이동_행_수(scene.transferId())).isEqualTo(1L);
    }

    // ── 픽스처 · 호출 도우미 ──────────────────────────────────────────────

    private Scene 이동_대기_장면() throws Exception {
        RunConfirmationFixtures fixtures = new RunConfirmationFixtures(academyRepository, busRepository,
                routeRepository, routeStopRepository, stopRepository, studentRepository, weeklyAddressRepository,
                runRepository);
        long academyId = fixtures.academyWithCoordinates();
        long fromRunId = 회차를_만든다(fixtures, academyId);
        long toRunId = 회차를_만든다(fixtures, academyId);
        long stopId = fixtures.stop(academyId, "37.560000", "126.970000");
        long studentId = fixtures.student(academyId, "이동학생");
        runForcedAdditionRepository.save(RunForcedAddition.forRun(fromRunId, studentId,
                fixtures.stop(academyId, "37.561000", "126.971000"), STAFF_ACCOUNT_ID, OffsetDateTime.now(clock),
                null));
        Scene draft = new Scene(academyId, fromRunId, toRunId, stopId, studentId, 0L);
        이동_등록한다(draft).andExpect(status().isCreated());
        동기화한다();
        long transferId = jdbcTemplate.queryForObject("SELECT id FROM run_transfer WHERE student_id = ?", Long.class,
                studentId);
        return new Scene(academyId, fromRunId, toRunId, stopId, studentId, transferId);
    }

    private long 회차를_만든다(RunConfirmationFixtures fixtures, long academyId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime departTime = now.plusMinutes(31);
        return fixtures.idleRun(academyId, fixtures.bus(academyId), now.toLocalDate(), Direction.TO_ACADEMY,
                departTime, departTime.minusMinutes(30));
    }

    /**
     * 시험 트랜잭션 안에서 JPA 변경을 DB 로 내리고 영속성 컨텍스트를 비운다 — 운영에서는 요청마다 컨텍스트가 새로
     * 열리므로, JDBC 로 심은 상태를 서비스가 읽고 서비스의 삭제를 JDBC 로 셀 때 이 동기화가 그 경계를 흉내 낸다.
     */
    private void 동기화한다() {
        entityManager.flush();
        entityManager.clear();
    }

    private void 회차_상태를_바꾼다(long runId, RunStatus runStatus) {
        동기화한다();
        jdbcTemplate.update("UPDATE run SET status = ? WHERE id = ?", runStatus.name().toLowerCase(), runId);
    }

    private long 이동_행_수(long transferId) {
        동기화한다();
        return jdbcTemplate.queryForObject("SELECT count(*) FROM run_transfer WHERE id = ?", Long.class, transferId);
    }

    private ResultActions 이동_등록한다(Scene scene) throws Exception {
        return mockMvc.perform(post("/api/v1/staff/students/" + scene.studentId() + "/transfer")
                .header("Authorization", 토큰(scene.academyId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"from_run_id\":" + scene.fromRunId() + ",\"to_run_id\":" + scene.toRunId()
                        + ",\"stop_id\":" + scene.stopId() + "}"));
    }

    private ResultActions 취소한다(long transferId, long academyId) throws Exception {
        return mockMvc.perform(delete("/api/v1/staff/transfers/" + transferId)
                .header("Authorization", 토큰(academyId)).header("X-Real-IP", CLIENT_IP));
    }

    /** §5.4 예정 명단의 학생 id 들 — 응답의 식별자는 JSON 문자열이다(Ruling 332). */
    private java.util.List<Long> 예정_명단_학생_id(long runId, long academyId) throws Exception {
        String json = mockMvc.perform(get("/api/v1/staff/runs/" + runId + "/roster")
                .header("Authorization", 토큰(academyId)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        java.util.List<String> ids = com.jayway.jsonpath.JsonPath.read(json, "$.data[*].student_id");
        return ids.stream().map(Long::valueOf).toList();
    }

    private String 토큰(long academyId) {
        return "Bearer " + tokenProvider.createAccessToken(STAFF_ACCOUNT_ID, academyId, Role.STAFF,
                AccountStatus.ACTIVE);
    }
}
