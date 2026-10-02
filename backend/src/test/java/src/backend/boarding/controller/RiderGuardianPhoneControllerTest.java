package src.backend.boarding.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import src.backend.academy.repository.AcademyRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.audit.entity.AuditAction;
import src.backend.audit.entity.AuditCategory;
import src.backend.audit.entity.AuditLog;
import src.backend.audit.repository.AuditLogRepository;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.common.enums.Weekday;
import src.backend.global.security.JwtTokenProvider;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.command.Phase9RosterFixtures;
import src.backend.run.command.RunConfirmationFixtures;
import src.backend.run.command.RunConfirmationService;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * 보호자 전화 원번호 단건 조회 {@code GET /runs/{runId}/riders/{riderId}/guardian-phone}(R46 privacy · Ruling 482·521) —
 * 명단(§4.2)은 마스킹(L2)을 유지하고, 매니저가 [전화] 를 누를 때만 그 학생 1명의 원번호를 준다.
 *
 * <p>인가는 명단과 같은 규약이다 — 배치되지 않은 회차 · 다른 학원의 회차는 구별 없이 {@code 403 FORBIDDEN}(§1.11),
 * 그 회차 명단에 없는 탑승자는 {@code 404 RIDER_NOT_FOUND}(§4.6 과 같은 코드). 호출마다 감사 1행({@code data_access read} ·
 * 대상 학생 · {@code fields: guardian_phone})이지만 같은 매니저·학생의 10분 안 재호출은 묶인다(Ruling 445).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RiderGuardianPhoneControllerTest {

    private static final String SERVICE_DATE = "2031-07-02";

    private static final String RAW_PHONE = "010-2345-8814";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenProvider tokenProvider;

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
    private ManagerRepository managerRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AssignmentRepository assignmentRepository;

    @Autowired
    private GuardianRepository guardianRepository;

    @Autowired
    private GuardianStudentRepository guardianStudentRepository;

    @Autowired
    private RunConfirmationService confirmationService;

    @Autowired
    private RunRiderRepository runRiderRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    private Phase9RosterFixtures fixtures() {
        RunConfirmationFixtures base = new RunConfirmationFixtures(academyRepository, busRepository, routeRepository,
                routeStopRepository, stopRepository, studentRepository, weeklyAddressRepository, runRepository);
        return new Phase9RosterFixtures(base, managerRepository, accountRepository, assignmentRepository,
                guardianRepository, guardianStudentRepository, confirmationService);
    }

    /** 한 학원의 확정 회차 1건 — 학생 1명(보호자 연결)이 탑승자로 올라 있고 매니저 1명이 배치돼 있다. */
    private Scene scene(boolean withGuardian) {
        Phase9RosterFixtures fx = fixtures();
        long academyId = fx.academyWithCoordinates();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.500000", "127.000000");
        fx.route(academyId, busId, Weekday.WED, Direction.TO_ACADEMY, stopId);
        long studentId = fx.student(academyId, "학생1");
        fx.verifiedAddress(studentId, stopId, Weekday.WED, Direction.TO_ACADEMY, "37.500000", "127.000000");
        if (withGuardian) {
            fx.guardianWithPhone(academyId, studentId, RAW_PHONE);
        }
        OffsetDateTime departTime = OffsetDateTime.parse("2031-07-02T08:00:00+09:00");
        long runId = fx.confirmedRun(academyId, busId, LocalDate.parse(SERVICE_DATE), Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        Phase9RosterFixtures.ManagerAccount manager = fx.manager(academyId, ManagerRole.ESCORT, "동승자");
        fx.assign(runId, manager.managerId(), ManagerRole.ESCORT);
        long riderId = runRiderRepository.findAllByRunIdAndAcademyId(runId, academyId).get(0).getId();
        return new Scene(fx, academyId, busId, stopId, studentId, runId, riderId, manager);
    }

    @Test
    void 배정_회차의_탑승_학생은_보호자_원번호를_받고_감사_1행이_남는다() throws Exception {
        Scene scene = scene(true);

        MvcResult result = mockMvc.perform(get(path(scene.runId(), scene.riderId()))
                .header("Authorization", 토큰(scene.manager().accountId(), scene.academyId())))
                .andExpect(status().isOk())
                .andReturn();

        assertThat((String) JsonPath.read(본문(result), "$.data.guardian_phone")).isEqualTo(RAW_PHONE);
        List<AuditLog> rows = 전화_감사_행(scene.studentId());
        assertThat(rows).hasSize(1);
        AuditLog row = rows.get(0);
        assertThat(row.getCategory()).isEqualTo(AuditCategory.DATA_ACCESS);
        assertThat(row.getAction()).isEqualTo(AuditAction.READ);
        assertThat(row.getActorAccountId()).isEqualTo(scene.manager().accountId());
        assertThat(row.getAcademyId()).isEqualTo(scene.academyId());
        assertThat(row.getIp()).as("조회 기록에도 접속 IP(Ruling 445 ③)").isNotBlank();
        assertThat(row.getDetail().get("fields")).isEqualTo(List.of("guardian_phone"));
    }

    @Test
    void 같은_매니저가_10분_안에_다시_눌러도_감사_행은_늘지_않는다() throws Exception {
        Scene scene = scene(true);

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get(path(scene.runId(), scene.riderId()))
                    .header("Authorization", 토큰(scene.manager().accountId(), scene.academyId())))
                    .andExpect(status().isOk());
        }

        assertThat(전화_감사_행(scene.studentId())).hasSize(1);
    }

    @Test
    void 명단을_먼저_본_학생이어도_원번호_조회는_별도_감사_행으로_남는다() throws Exception {
        Scene scene = scene(true);
        String token = 토큰(scene.manager().accountId(), scene.academyId());

        mockMvc.perform(get("/api/v1/runs/" + scene.runId() + "/roster").header("Authorization", token))
                .andExpect(status().isOk());
        mockMvc.perform(get(path(scene.runId(), scene.riderId())).header("Authorization", token))
                .andExpect(status().isOk());

        assertThat(전화_감사_행(scene.studentId()))
                .as("명단 조회(photo_url·note·address)가 10분 묶기 창을 먼저 열어도 원번호(guardian_phone) 접근은 기록된다")
                .hasSize(1);
    }

    @Test
    void 배정되지_않은_회차는_403이고_감사_행이_없다() throws Exception {
        Scene scene = scene(true);
        Phase9RosterFixtures.ManagerAccount other = scene.fx().manager(scene.academyId(), ManagerRole.DRIVER, "다른기사");

        mockMvc.perform(get(path(scene.runId(), scene.riderId()))
                .header("Authorization", 토큰(other.accountId(), scene.academyId(), Role.DRIVER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertThat(전화_감사_행(scene.studentId())).isEmpty();
    }

    @Test
    void 다른_학원_매니저는_403이다() throws Exception {
        Scene scene = scene(true);
        long otherAcademyId = scene.fx().academyWithCoordinates();
        Phase9RosterFixtures.ManagerAccount outsider = scene.fx().manager(otherAcademyId, ManagerRole.ESCORT, "타학원");

        mockMvc.perform(get(path(scene.runId(), scene.riderId()))
                .header("Authorization", 토큰(outsider.accountId(), otherAcademyId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertThat(전화_감사_행(scene.studentId())).isEmpty();
    }

    @Test
    void 이_회차_명단에_없는_탑승자는_404이다() throws Exception {
        Scene scene = scene(true);
        OffsetDateTime depart = OffsetDateTime.parse("2031-07-02T09:00:00+09:00");
        long otherRunId = scene.fx().idleRun(scene.academyId(), scene.busId(), LocalDate.parse(SERVICE_DATE),
                Direction.FROM_ACADEMY, depart, depart.minusMinutes(30));
        long otherStudentId = scene.fx().student(scene.academyId(), "다른회차학생");
        scene.fx().guardianWithPhone(scene.academyId(), otherStudentId, "010-9999-0000");
        jdbcTemplate.update("INSERT INTO run_rider (run_id, student_id, stop_id, status) VALUES (?, ?, ?, 'waiting')",
                otherRunId, otherStudentId, scene.stopId());
        long otherRiderId = jdbcTemplate.queryForObject("SELECT id FROM run_rider WHERE run_id = ?", Long.class,
                otherRunId);
        String token = 토큰(scene.manager().accountId(), scene.academyId());

        mockMvc.perform(get(path(scene.runId(), otherRiderId)).header("Authorization", token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RIDER_NOT_FOUND"));
        mockMvc.perform(get(path(scene.runId(), 999_999_999L)).header("Authorization", token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RIDER_NOT_FOUND"));

        assertThat(전화_감사_행(otherStudentId)).isEmpty();
    }

    /** 명단에서 빠진 {@code absent} 탑승자는 이 회차 탑승자가 아니라 404 다(API_SPEC §4.2.1) — 번호도 감사 행도 나가지 않는다. */
    @Test
    void 결석_처리된_탑승자는_404이고_감사_행이_없다() throws Exception {
        Scene scene = scene(true);
        // 엔티티로 바꾼다 — 시험이 한 트랜잭션이라 JDBC 로 바꾸면 이미 적재된 엔티티가 옛 상태 그대로 조회에 쓰인다
        runRiderRepository.findById(scene.riderId()).orElseThrow().markAbsent(OffsetDateTime.now());

        mockMvc.perform(get(path(scene.runId(), scene.riderId()))
                .header("Authorization", 토큰(scene.manager().accountId(), scene.academyId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RIDER_NOT_FOUND"));

        assertThat(전화_감사_행(scene.studentId())).isEmpty();
    }

    @Test
    void 보호자가_연결되지_않은_학생은_번호_null이고_감사_행이_없다() throws Exception {
        Scene scene = scene(false);

        MvcResult result = mockMvc.perform(get(path(scene.runId(), scene.riderId()))
                .header("Authorization", 토큰(scene.manager().accountId(), scene.academyId())))
                .andExpect(status().isOk())
                .andReturn();

        assertThat((Object) JsonPath.read(본문(result), "$.data.guardian_phone")).isNull();
        assertThat(전화_감사_행(scene.studentId())).as("값이 실리지 않았으면 기록하지 않는다").isEmpty();
    }

    private String path(long runId, long riderId) {
        return "/api/v1/runs/" + runId + "/riders/" + riderId + "/guardian-phone";
    }

    /** 이 학생의 보호자 연락처 원본 접근을 적은 감사 행 — 명단 조회 행(target_type=run_roster)은 세지 않는다. */
    private List<AuditLog> 전화_감사_행(long studentId) {
        return auditLogRepository.findAll().stream()
                .filter(log -> log.getCategory() == AuditCategory.DATA_ACCESS && "student".equals(log.getTargetType())
                        && log.getTargetId() != null && log.getTargetId().equals(studentId)
                        && List.of("guardian_phone").equals(log.getDetail().get("fields")))
                .toList();
    }

    private String 토큰(long accountId, long academyId) {
        return 토큰(accountId, academyId, Role.ESCORT);
    }

    private String 토큰(long accountId, long academyId, Role role) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, role, AccountStatus.ACTIVE);
    }

    private String 본문(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private record Scene(Phase9RosterFixtures fx, long academyId, long busId, long stopId, long studentId, long runId,
            long riderId, Phase9RosterFixtures.ManagerAccount manager) {
    }
}
