package src.backend.run.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;

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
 * §4.1 {@code GET /manager/runs} — RUN-01·M-02·M-07, Phase 9 목표 16·17.
 *
 * <p>이 클래스의 축은 <b>배치 여부가 곧 목록 범위</b>라는 것이다({@code ManagerRunAccess} 대신
 * {@code assignment} 조회 자체가 좁힌다). 배치되지 않은 매니저가 빈 배열을 받는지(목표 17), 삭제된
 * 매니저의 옛 토큰이 로그인은 통과해도 이 목록에서 막히는지(목표 16)를 함께 본다 — 하나만 보면
 * "배치가 있어야 보인다" 와 "탈퇴하면 안 보인다" 가 서로 가려서 둘 다 통과하는 반쪽짜리 구현이
 * 나올 수 있다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ManagerRunControllerTest {

    private static final String SERVICE_DATE = "2031-07-01";

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

    private Phase9RosterFixtures fixtures() {
        RunConfirmationFixtures base = new RunConfirmationFixtures(academyRepository, busRepository, routeRepository,
                routeStopRepository, stopRepository, studentRepository, weeklyAddressRepository, runRepository);
        return new Phase9RosterFixtures(base, managerRepository, accountRepository, assignmentRepository,
                guardianRepository, guardianStudentRepository, confirmationService);
    }

    @Test
    void 배치받지_않은_매니저는_빈_목록을_받는다() throws Exception {
        Phase9RosterFixtures fx = fixtures();
        long academyId = fx.academyWithCoordinates();
        Phase9RosterFixtures.ManagerAccount manager = fx.manager(academyId, ManagerRole.DRIVER, "미배치기사");

        MvcResult result = mockMvc
                .perform(get("/api/v1/manager/runs").param("date", SERVICE_DATE).header("Authorization",
                        토큰(manager.accountId(), academyId, Role.DRIVER)))
                .andExpect(status().isOk())
                .andReturn();

        java.util.List<?> items = JsonPath.read(본문(result), "$.data");
        assertThat(items).isEmpty();
    }

    @Test
    void 배치된_매니저는_자기_회차만_역할과_함께_받는다() throws Exception {
        Phase9RosterFixtures fx = fixtures();
        long academyId = fx.academyWithCoordinates();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.500000", "127.000000");
        fx.route(academyId, busId, Weekday.TUE, Direction.TO_ACADEMY, stopId);
        long studentId = fx.student(academyId, "학생1");
        fx.verifiedAddress(studentId, stopId, Weekday.TUE, Direction.TO_ACADEMY, "37.500000", "127.000000");
        LocalDate serviceDate = LocalDate.parse(SERVICE_DATE);
        OffsetDateTime departTime = OffsetDateTime.parse("2031-07-01T08:00:00+09:00");
        long runId = fx.confirmedRun(academyId, busId, serviceDate, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));

        Phase9RosterFixtures.ManagerAccount manager = fx.manager(academyId, ManagerRole.ESCORT, "배치동승자");
        fx.assign(runId, manager.managerId(), ManagerRole.ESCORT);

        MvcResult result = mockMvc
                .perform(get("/api/v1/manager/runs").param("date", SERVICE_DATE).header("Authorization",
                        토큰(manager.accountId(), academyId, Role.ESCORT)))
                .andExpect(status().isOk())
                .andReturn();

        String body = 본문(result);
        java.util.List<?> items = JsonPath.read(body, "$.data");
        assertThat(items).hasSize(1);
        assertThat((Integer) JsonPath.read(body, "$.data[0].run_id")).isEqualTo((int) runId);
        assertThat((String) JsonPath.read(body, "$.data[0].role_in_run")).isEqualTo("escort");
    }

    @Test
    void 배치는_남아도_탈퇴한_매니저의_토큰은_403이다() throws Exception {
        Phase9RosterFixtures fx = fixtures();
        long academyId = fx.academyWithCoordinates();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.500000", "127.000000");
        fx.route(academyId, busId, Weekday.TUE, Direction.TO_ACADEMY, stopId);
        long studentId = fx.student(academyId, "학생1");
        fx.verifiedAddress(studentId, stopId, Weekday.TUE, Direction.TO_ACADEMY, "37.500000", "127.000000");
        LocalDate serviceDate = LocalDate.parse(SERVICE_DATE);
        OffsetDateTime departTime = OffsetDateTime.parse("2031-07-01T08:00:00+09:00");
        long runId = fx.confirmedRun(academyId, busId, serviceDate, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));

        Phase9RosterFixtures.ManagerAccount manager = fx.manager(academyId, ManagerRole.DRIVER, "탈퇴기사");
        fx.assign(runId, manager.managerId(), ManagerRole.DRIVER);
        fx.softDeleteManager(manager.managerId());

        mockMvc.perform(get("/api/v1/manager/runs").param("date", SERVICE_DATE).header("Authorization",
                토큰(manager.accountId(), academyId, Role.DRIVER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void 같은_학원_다른_매니저에게는_이_회차가_보이지_않는다() throws Exception {
        Phase9RosterFixtures fx = fixtures();
        long academyId = fx.academyWithCoordinates();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.500000", "127.000000");
        fx.route(academyId, busId, Weekday.TUE, Direction.TO_ACADEMY, stopId);
        long studentId = fx.student(academyId, "학생1");
        fx.verifiedAddress(studentId, stopId, Weekday.TUE, Direction.TO_ACADEMY, "37.500000", "127.000000");
        LocalDate serviceDate = LocalDate.parse(SERVICE_DATE);
        OffsetDateTime departTime = OffsetDateTime.parse("2031-07-01T08:00:00+09:00");
        long runId = fx.confirmedRun(academyId, busId, serviceDate, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));

        Phase9RosterFixtures.ManagerAccount owner = fx.manager(academyId, ManagerRole.DRIVER, "배치기사");
        fx.assign(runId, owner.managerId(), ManagerRole.DRIVER);
        Phase9RosterFixtures.ManagerAccount other = fx.manager(academyId, ManagerRole.ESCORT, "미배치동승자");

        MvcResult result = mockMvc
                .perform(get("/api/v1/manager/runs").param("date", SERVICE_DATE).header("Authorization",
                        토큰(other.accountId(), academyId, Role.ESCORT)))
                .andExpect(status().isOk())
                .andReturn();

        java.util.List<?> items = JsonPath.read(본문(result), "$.data");
        assertThat(items).isEmpty();
    }

    private String 토큰(long accountId, long academyId, Role role) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, role, AccountStatus.ACTIVE);
    }

    private String 본문(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
