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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import src.backend.academy.repository.AcademyRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.entity.RunRider;
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
 * §4.2 {@code GET /runs/{runId}/roster} — RST-01·02·04·M-03, Phase 9 목표 6·15·16.
 *
 * <p>이 클래스와 {@link StaffRosterControllerTest} 는 <b>같은 원본을 반대로 검사</b>한다 —
 * 이쪽은 보호자 연락처가 <b>가려지는지</b>(목표 6)와 결석 학생이 <b>행에서 빠지고 집계에만
 * 남는지</b>(목표 15)를 본다. 관계자 웹 쪽 클래스가 정반대(원문 노출·결석도 행 유지)를 검사해야
 * 두 화면이 같은 데이터에서 서로 다르게 갈리는 지점이 실제로 검사된다.
 *
 * <p>목표 16(Ruling 148·192)은 {@code ManagerRunControllerTest} 가 목록(§4.1)에서 이미 검사하지만,
 * {@code ManagerRunAccess.requireManager} 는 {@code requireAssignedRun} 을 거쳐 이 명단 엔드포인트도
 * 지나므로 여기서도 같은 조건을 별도로 두드린다 — 정본의 완료 조건이 "회차 조회 거부"·"명단 조회
 * 거부" 둘을 각각 명시한다({@code p9-goal-table.md} 목표 16 행).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RunRosterControllerTest {

    private static final String SERVICE_DATE = "2031-07-02";

    @Autowired
    private MockMvc mockMvc;

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

    private Phase9RosterFixtures fixtures() {
        RunConfirmationFixtures base = new RunConfirmationFixtures(academyRepository, busRepository, routeRepository,
                routeStopRepository, stopRepository, studentRepository, weeklyAddressRepository, runRepository);
        return new Phase9RosterFixtures(base, managerRepository, accountRepository, assignmentRepository,
                guardianRepository, guardianStudentRepository, confirmationService);
    }

    @Test
    void 보호자_연락처는_마스킹돼_내려간다() throws Exception {
        Phase9RosterFixtures fx = fixtures();
        long academyId = fx.academyWithCoordinates();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.500000", "127.000000");
        fx.route(academyId, busId, Weekday.WED, Direction.TO_ACADEMY, stopId);
        long studentId = fx.student(academyId, "학생1");
        fx.verifiedAddress(studentId, stopId, Weekday.WED, Direction.TO_ACADEMY, "37.500000", "127.000000");
        fx.guardianWithPhone(academyId, studentId, "010-2345-8814");
        OffsetDateTime departTime = OffsetDateTime.parse("2031-07-02T08:00:00+09:00");
        long runId = fx.confirmedRun(academyId, busId, LocalDate.parse(SERVICE_DATE), Direction.TO_ACADEMY,
                departTime, departTime.minusMinutes(30));
        Phase9RosterFixtures.ManagerAccount manager = fx.manager(academyId, ManagerRole.DRIVER, "기사");
        fx.assign(runId, manager.managerId(), ManagerRole.DRIVER);

        MvcResult result = mockMvc
                .perform(get("/api/v1/runs/" + runId + "/roster").header("Authorization",
                        토큰(manager.accountId(), academyId, Role.DRIVER)))
                .andExpect(status().isOk())
                .andReturn();

        String body = 본문(result);
        assertThat((String) JsonPath.read(body, "$.data.stops[0].students[0].guardian_phone"))
                .isEqualTo("010-2XXX-8814");
    }

    @Test
    void 결석_학생은_명단에서_빠지고_집계에만_남는다() throws Exception {
        Phase9RosterFixtures fx = fixtures();
        long academyId = fx.academyWithCoordinates();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.500000", "127.000000");
        fx.route(academyId, busId, Weekday.WED, Direction.TO_ACADEMY, stopId);
        long student1 = fx.student(academyId, "학생1");
        long student2 = fx.student(academyId, "학생2");
        fx.verifiedAddress(student1, stopId, Weekday.WED, Direction.TO_ACADEMY, "37.500000", "127.000000");
        fx.verifiedAddress(student2, stopId, Weekday.WED, Direction.TO_ACADEMY, "37.500000", "127.000000");
        OffsetDateTime departTime = OffsetDateTime.parse("2031-07-02T08:00:00+09:00");
        long runId = fx.confirmedRun(academyId, busId, LocalDate.parse(SERVICE_DATE), Direction.TO_ACADEMY,
                departTime, departTime.minusMinutes(30));
        결석_처리한다(academyId, runId, student2);
        Phase9RosterFixtures.ManagerAccount manager = fx.manager(academyId, ManagerRole.ESCORT, "동승자");
        fx.assign(runId, manager.managerId(), ManagerRole.ESCORT);

        MvcResult result = mockMvc
                .perform(get("/api/v1/runs/" + runId + "/roster").header("Authorization",
                        토큰(manager.accountId(), academyId, Role.ESCORT)))
                .andExpect(status().isOk())
                .andReturn();

        String body = 본문(result);
        List<?> students = JsonPath.read(body, "$.data.stops[0].students");
        assertThat(students).hasSize(1);
        assertThat((String) JsonPath.read(body, "$.data.stops[0].students[0].name")).isEqualTo("학생1");
        assertThat((Integer) JsonPath.read(body, "$.data.counts.absent_n")).isEqualTo(1);
    }

    @Test
    void 확정_전_회차는_409_RUN_NOT_CONFIRMED_다() throws Exception {
        Phase9RosterFixtures fx = fixtures();
        long academyId = fx.academyWithCoordinates();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.500000", "127.000000");
        fx.route(academyId, busId, Weekday.WED, Direction.TO_ACADEMY, stopId);
        OffsetDateTime departTime = OffsetDateTime.parse("2031-07-02T08:00:00+09:00");
        long runId = fx.idleRun(academyId, busId, LocalDate.parse(SERVICE_DATE), Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        Phase9RosterFixtures.ManagerAccount manager = fx.manager(academyId, ManagerRole.DRIVER, "기사");
        fx.assign(runId, manager.managerId(), ManagerRole.DRIVER);

        mockMvc.perform(get("/api/v1/runs/" + runId + "/roster").header("Authorization",
                토큰(manager.accountId(), academyId, Role.DRIVER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_CONFIRMED"));
    }

    @Test
    void 배치는_남아도_탈퇴한_매니저의_토큰은_403이다() throws Exception {
        Phase9RosterFixtures fx = fixtures();
        long academyId = fx.academyWithCoordinates();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.500000", "127.000000");
        fx.route(academyId, busId, Weekday.WED, Direction.TO_ACADEMY, stopId);
        OffsetDateTime departTime = OffsetDateTime.parse("2031-07-02T08:00:00+09:00");
        long runId = fx.confirmedRun(academyId, busId, LocalDate.parse(SERVICE_DATE), Direction.TO_ACADEMY,
                departTime, departTime.minusMinutes(30));
        Phase9RosterFixtures.ManagerAccount manager = fx.manager(academyId, ManagerRole.DRIVER, "탈퇴기사");
        fx.assign(runId, manager.managerId(), ManagerRole.DRIVER);
        fx.softDeleteManager(manager.managerId());

        mockMvc.perform(get("/api/v1/runs/" + runId + "/roster").header("Authorization",
                토큰(manager.accountId(), academyId, Role.DRIVER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void 배치되지_않은_매니저는_403이다() throws Exception {
        Phase9RosterFixtures fx = fixtures();
        long academyId = fx.academyWithCoordinates();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.500000", "127.000000");
        fx.route(academyId, busId, Weekday.WED, Direction.TO_ACADEMY, stopId);
        OffsetDateTime departTime = OffsetDateTime.parse("2031-07-02T08:00:00+09:00");
        long runId = fx.confirmedRun(academyId, busId, LocalDate.parse(SERVICE_DATE), Direction.TO_ACADEMY,
                departTime, departTime.minusMinutes(30));
        Phase9RosterFixtures.ManagerAccount manager = fx.manager(academyId, ManagerRole.DRIVER, "미배치기사");

        mockMvc.perform(get("/api/v1/runs/" + runId + "/roster").header("Authorization",
                토큰(manager.accountId(), academyId, Role.DRIVER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    private void 결석_처리한다(long academyId, long runId, long studentId) {
        List<RunRider> riders = runRiderRepository.findAllByRunIdAndAcademyId(runId, academyId);
        RunRider target = riders.stream().filter(rider -> rider.getStudentId() == studentId).findFirst()
                .orElseThrow();
        target.markAbsent(OffsetDateTime.now());
        runRiderRepository.save(target);
    }

    private String 토큰(long accountId, long academyId, Role role) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, role, AccountStatus.ACTIVE);
    }

    private String 본문(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
