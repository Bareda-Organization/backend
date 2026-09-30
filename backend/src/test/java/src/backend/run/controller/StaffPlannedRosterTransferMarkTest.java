package src.backend.run.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

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
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import src.backend.academy.repository.AcademyRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Role;
import src.backend.global.common.enums.Weekday;
import src.backend.global.security.JwtTokenProvider;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.command.RunConfirmationFixtures;
import src.backend.run.command.RunConfirmationService;
import src.backend.run.entity.RunForcedAddition;
import src.backend.run.repository.RunForcedAdditionRepository;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;
import testsupport.clock.FixedClock20260827Config;

/**
 * §5.4 예정 명단의 {@code change}·{@code transfer_id}(Ruling 369·370) — R36-BE 목표 7·8.
 *
 * <p>도착 회차 하나에 세 종류(요일별 주소 · 강제 추가 · 이동 대기)를 함께 두고 행마다 대조한다 —
 * 한 종류씩 따로 재면 "이동 행만 표시하는" 구현도 통과한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(FixedClock20260827Config.class)
class StaffPlannedRosterTransferMarkTest {

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

    @Autowired
    private RunConfirmationService confirmationService;

    /** 도착 회차 하나에 요일별 주소 · 강제 추가 · 이동 대기 학생을 한 명씩 둔 장면. */
    private record Scene(long academyId, long toRunId, long weeklyStudent, long forcedStudent, long movedStudent,
            long transferId) {
    }

    @Test
    @DisplayName("목표 7 — idle 도착 회차: 이동 행 change=added + transfer_id(문자열) · 강제 추가 행 added + null · 요일별 주소 행 둘 다 null")
    void 예정_명단이_세_종류를_구분해_표시한다() throws Exception {
        Scene scene = 장면을_만든다();

        Map<String, Map<String, Object>> rows = 명단(scene.toRunId(), scene.academyId());

        Map<String, Object> weekly = rows.get(String.valueOf(scene.weeklyStudent()));
        assertThat(weekly.get("change")).as("요일별 주소 행 change").isNull();
        assertThat(weekly.get("transfer_id")).as("요일별 주소 행 transfer_id").isNull();
        Map<String, Object> forced = rows.get(String.valueOf(scene.forcedStudent()));
        assertThat(forced.get("change")).as("강제 추가 행 change").isEqualTo("added");
        assertThat(forced.get("transfer_id")).as("강제 추가 행 transfer_id").isNull();
        Map<String, Object> moved = rows.get(String.valueOf(scene.movedStudent()));
        assertThat(moved.get("change")).as("이동 행 change").isEqualTo("added");
        assertThat(moved.get("transfer_id")).as("이동 행 transfer_id 는 JSON 문자열").isEqualTo(
                String.valueOf(scene.transferId()));
    }

    @Test
    @DisplayName("목표 8 — 확정 뒤 명단은 run_rider 그대로이고 transfer_id 는 null 이다")
    void 확정_뒤_명단은_transfer_id_가_없다() throws Exception {
        Scene scene = 장면을_만든다();
        entityManager.flush();
        entityManager.clear();

        confirmationService.confirmOne(scene.toRunId());

        Map<String, Object> moved = 명단(scene.toRunId(), scene.academyId()).get(String.valueOf(scene.movedStudent()));
        assertThat(moved.get("change")).as("확정이 붙인 change").isEqualTo("added");
        assertThat(moved.get("transfer_id")).as("확정 뒤 transfer_id").isNull();
    }

    // ── 픽스처 · 호출 도우미 ──────────────────────────────────────────────

    private Scene 장면을_만든다() throws Exception {
        RunConfirmationFixtures fixtures = new RunConfirmationFixtures(academyRepository, busRepository,
                routeRepository, routeStopRepository, stopRepository, studentRepository, weeklyAddressRepository,
                runRepository);
        long academyId = fixtures.academyWithCoordinates();
        long toBusId = fixtures.bus(academyId);
        long toRunId = 회차를_만든다(fixtures, academyId, toBusId);
        long fromRunId = 회차를_만든다(fixtures, academyId, fixtures.bus(academyId));
        long routeStop = fixtures.stop(academyId, "37.560000", "126.970000");
        long lastStop = fixtures.stop(academyId, "37.562000", "126.972000");
        fixtures.route(academyId, toBusId, Weekday.THU, Direction.TO_ACADEMY, routeStop, lastStop);

        long weeklyStudent = fixtures.student(academyId, "요일별학생");
        fixtures.verifiedAddress(weeklyStudent, routeStop, Weekday.THU, Direction.TO_ACADEMY, "37.560000", "126.970000");
        long forcedStudent = fixtures.student(academyId, "강제추가학생");
        runForcedAdditionRepository.save(RunForcedAddition.forRun(toRunId, forcedStudent,
                fixtures.stop(academyId, "37.561000", "126.971000"), STAFF_ACCOUNT_ID, OffsetDateTime.now(clock),
                null));
        // 이동 학생은 출발 회차 명단에 있어야 이동을 등록할 수 있다 — 강제 추가 1건으로 올린다.
        long movedStudent = fixtures.student(academyId, "이동학생");
        runForcedAdditionRepository.save(RunForcedAddition.forRun(fromRunId, movedStudent,
                fixtures.stop(academyId, "37.563000", "126.973000"), STAFF_ACCOUNT_ID, OffsetDateTime.now(clock),
                null));
        mockMvc.perform(post("/api/v1/staff/students/" + movedStudent + "/transfer")
                .header("Authorization", 토큰(academyId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"from_run_id\":" + fromRunId + ",\"to_run_id\":" + toRunId + ",\"stop_id\":" + routeStop
                        + "}")).andExpect(status().isCreated());
        entityManager.flush();
        long transferId = jdbcTemplate.queryForObject("SELECT id FROM run_transfer WHERE student_id = ?", Long.class,
                movedStudent);
        return new Scene(academyId, toRunId, weeklyStudent, forcedStudent, movedStudent, transferId);
    }

    private long 회차를_만든다(RunConfirmationFixtures fixtures, long academyId, long busId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime departTime = now.plusMinutes(31);
        return fixtures.idleRun(academyId, busId, now.toLocalDate(), Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
    }

    /** §5.4 응답을 {@code student_id} 문자열을 키로 한 행 표로 읽는다. */
    private Map<String, Map<String, Object>> 명단(long runId, long academyId) throws Exception {
        String json = mockMvc.perform(get("/api/v1/staff/runs/" + runId + "/roster")
                .header("Authorization", 토큰(academyId)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> rows = JsonPath.read(json, "$.data");
        return rows.stream().collect(java.util.stream.Collectors.toMap(row -> (String) row.get("student_id"),
                row -> row));
    }

    private String 토큰(long academyId) {
        return "Bearer " + tokenProvider.createAccessToken(STAFF_ACCOUNT_ID, academyId, Role.STAFF,
                AccountStatus.ACTIVE);
    }
}
