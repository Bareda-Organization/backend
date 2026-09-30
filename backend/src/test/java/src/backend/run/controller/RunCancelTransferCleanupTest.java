package src.backend.run.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

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

import com.jayway.jsonpath.JsonPath;

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
import src.backend.run.repository.RunForcedAdditionRepository;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;
import testsupport.clock.FixedClock20260827Config;

/**
 * Ruling 372 — 회차 임시 취소와 이동 대기(R36-BE 2차, 목표 10a).
 *
 * <p>① 도착 회차가 임시 취소되면 그 회차로 들어오는 {@code staged} 이동을 지운다(관계자 경로 · 스케줄 경로). ② §5.8.1 취소는
 * 임시 취소된 회차를 막지 않는다. {@code applied} 이동은 회차 취소로 지워지지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(FixedClock20260827Config.class)
class RunCancelTransferCleanupTest {

    private static final long STAFF_ACCOUNT_ID = 9101L;

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

    /** 학원 1곳에 출발·도착 회차와 이동 대기 1건을 갖춘 장면. */
    private record Scene(long academyId, long fromRunId, long toRunId, long stopId, long studentId, long transferId) {
    }

    @Test
    @DisplayName("목표 10a-1 — 관계자가 도착 회차를 임시 취소하면 들어오는 staged 이동이 지워지고 감사가 남고 학생이 출발 명단으로 돌아온다")
    void 관계자_경로로_도착_회차를_취소하면_들어오는_이동_대기가_지워진다() throws Exception {
        Scene scene = 오늘_장면();

        mockMvc.perform(delete("/api/v1/staff/runs/" + scene.toRunId()).header("Authorization", 토큰(scene.academyId())))
                .andExpect(status().isNoContent());

        assertThat(이동_행_수(scene.transferId())).as("이동 행").isZero();
        assertThat(삭제_감사_수(scene.transferId())).as("감사 기록").isEqualTo(1L);
        assertThat(예정_명단_학생_id(scene.fromRunId(), scene.academyId())).contains(scene.studentId());
    }

    @Test
    @DisplayName("목표 10a-2 — 스케줄 삭제가 도착 회차를 취소해도 같은 결과다")
    void 스케줄_경로로_도착_회차가_취소돼도_들어오는_이동_대기가_지워진다() throws Exception {
        RunConfirmationFixtures fixtures = fixtures();
        long academyId = fixtures.academyWithCoordinates();
        long toBusId = fixtures.bus(academyId);
        String register = mockMvc.perform(post("/api/v1/staff/schedules")
                .header("Authorization", 토큰(academyId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bus_id\":" + toBusId + ",\"weekday\":\"fri\",\"direction\":\"to_academy\","
                        + "\"depart_time\":\"08:00\",\"origin_name\":\"집결지\",\"destination_name\":\"학원\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long scheduleId = Long.parseLong(JsonPath.read(register, "$.data.id"));
        entityManager.flush();
        long toRunId = jdbcTemplate.queryForObject("SELECT id FROM run WHERE schedule_id = ?", Long.class, scheduleId);
        LocalDate tomorrow = LocalDate.now(clock).plusDays(1);
        long fromRunId = fixtures.idleRun(academyId, fixtures.bus(academyId), tomorrow, Direction.TO_ACADEMY,
                tomorrow.atTime(8, 0).atZone(clock.getZone()).toOffsetDateTime(),
                tomorrow.atTime(7, 30).atZone(clock.getZone()).toOffsetDateTime());
        long stopId = fixtures.stop(academyId, "37.560000", "126.970000");
        long studentId = fixtures.student(academyId, "이동학생");
        runForcedAdditionRepository.save(RunForcedAddition.forRun(fromRunId, studentId,
                fixtures.stop(academyId, "37.561000", "126.971000"), STAFF_ACCOUNT_ID, OffsetDateTime.now(clock), null));
        이동_등록한다(new Scene(academyId, fromRunId, toRunId, stopId, studentId, 0L)).andExpect(status().isCreated());
        entityManager.flush();
        long transferId = jdbcTemplate.queryForObject("SELECT id FROM run_transfer WHERE student_id = ?", Long.class,
                studentId);

        mockMvc.perform(delete("/api/v1/staff/schedules/" + scheduleId).header("Authorization", 토큰(academyId)))
                .andExpect(status().isNoContent());

        assertThat(이동_행_수(transferId)).as("이동 행").isZero();
        assertThat(삭제_감사_수(transferId)).as("감사 기록").isEqualTo(1L);
        assertThat(예정_명단_학생_id(fromRunId, academyId)).contains(studentId);
    }

    @Test
    @DisplayName("목표 10a-3 — 출발 회차가 임시 취소된 이동은 §5.8.1 로 지워 204 이고, 도착 회차가 confirmed 면 여전히 403 이다")
    void 출발_회차가_취소된_이동은_지울_수_있고_도착이_확정이면_막힌다() throws Exception {
        Scene erasable = 오늘_장면();
        mockMvc.perform(delete("/api/v1/staff/runs/" + erasable.fromRunId())
                .header("Authorization", 토큰(erasable.academyId()))).andExpect(status().isNoContent());
        assertThat(이동_행_수(erasable.transferId())).as("출발 회차 취소는 이동을 지우지 않는다").isEqualTo(1L);

        mockMvc.perform(delete("/api/v1/staff/transfers/" + erasable.transferId())
                .header("Authorization", 토큰(erasable.academyId()))).andExpect(status().isNoContent());
        assertThat(이동_행_수(erasable.transferId())).isZero();

        Scene blocked = 오늘_장면();
        mockMvc.perform(delete("/api/v1/staff/runs/" + blocked.fromRunId())
                .header("Authorization", 토큰(blocked.academyId()))).andExpect(status().isNoContent());
        동기화한다();
        jdbcTemplate.update("UPDATE run SET status = 'confirmed' WHERE id = ?", blocked.toRunId());
        mockMvc.perform(delete("/api/v1/staff/transfers/" + blocked.transferId())
                .header("Authorization", 토큰(blocked.academyId()))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CHANGE_WINDOW_CLOSED"));
        assertThat(이동_행_수(blocked.transferId())).isEqualTo(1L);
    }

    @Test
    @DisplayName("목표 10a-4 — applied 이동은 도착 회차를 임시 취소해도 지워지지 않는다")
    void 반영된_이동은_회차_취소로_지워지지_않는다() throws Exception {
        Scene scene = 오늘_장면();
        동기화한다();
        jdbcTemplate.update("UPDATE run_transfer SET status = 'applied', applied_at = now() WHERE id = ?",
                scene.transferId());

        mockMvc.perform(delete("/api/v1/staff/runs/" + scene.toRunId()).header("Authorization", 토큰(scene.academyId())))
                .andExpect(status().isNoContent());

        assertThat(이동_행_수(scene.transferId())).isEqualTo(1L);
        assertThat(삭제_감사_수(scene.transferId())).isZero();
    }

    // ── 픽스처 · 호출 도우미 ──────────────────────────────────────────────

    private RunConfirmationFixtures fixtures() {
        return new RunConfirmationFixtures(academyRepository, busRepository, routeRepository, routeStopRepository,
                stopRepository, studentRepository, weeklyAddressRepository, runRepository);
    }

    private Scene 오늘_장면() throws Exception {
        RunConfirmationFixtures fixtures = fixtures();
        long academyId = fixtures.academyWithCoordinates();
        long fromRunId = 회차를_만든다(fixtures, academyId);
        long toRunId = 회차를_만든다(fixtures, academyId);
        long stopId = fixtures.stop(academyId, "37.560000", "126.970000");
        long studentId = fixtures.student(academyId, "이동학생");
        runForcedAdditionRepository.save(RunForcedAddition.forRun(fromRunId, studentId,
                fixtures.stop(academyId, "37.561000", "126.971000"), STAFF_ACCOUNT_ID, OffsetDateTime.now(clock), null));
        이동_등록한다(new Scene(academyId, fromRunId, toRunId, stopId, studentId, 0L)).andExpect(status().isCreated());
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

    /** 시험 트랜잭션 안에서 JPA 변경을 내리고 컨텍스트를 비운다 — JDBC 로 세고 심는 경계를 운영의 요청 경계처럼 만든다. */
    private void 동기화한다() {
        entityManager.flush();
        entityManager.clear();
    }

    private long 이동_행_수(long transferId) {
        동기화한다();
        return jdbcTemplate.queryForObject("SELECT count(*) FROM run_transfer WHERE id = ?", Long.class, transferId);
    }

    private long 삭제_감사_수(long transferId) {
        동기화한다();
        return jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log WHERE category = 'data_access' "
                + "AND action = 'delete' AND target_type = 'run_transfer' AND target_id = ?", Long.class, transferId);
    }

    private ResultActions 이동_등록한다(Scene scene) throws Exception {
        return mockMvc.perform(post("/api/v1/staff/students/" + scene.studentId() + "/transfer")
                .header("Authorization", 토큰(scene.academyId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"from_run_id\":" + scene.fromRunId() + ",\"to_run_id\":" + scene.toRunId()
                        + ",\"stop_id\":" + scene.stopId() + "}"));
    }

    private List<Long> 예정_명단_학생_id(long runId, long academyId) throws Exception {
        동기화한다();
        String json = mockMvc.perform(get("/api/v1/staff/runs/" + runId + "/roster")
                .header("Authorization", 토큰(academyId)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(json, "$.data[*].student_id");
        return ids.stream().map(Long::valueOf).toList();
    }

    private String 토큰(long academyId) {
        return "Bearer " + tokenProvider.createAccessToken(STAFF_ACCOUNT_ID, academyId, Role.STAFF,
                AccountStatus.ACTIVE);
    }
}
