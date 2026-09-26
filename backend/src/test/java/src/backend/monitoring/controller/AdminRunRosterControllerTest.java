package src.backend.monitoring.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.repository.AcademyRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
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
 * {@code GET /admin/runs/{runId}/roster}(API_SPEC §6.9, O-06) — {@code guardian_phone} 하나에
 * 집중한다. 같은 컬럼을 읽는 §4.2·§5.4 는 {@code RunRosterControllerTest} 가 이미 검사하지만,
 * §6.9(메인 관리자 콘솔)는 아직 전담 시험이 없어 {@code Ruling 359} 로 새로 만든다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminRunRosterControllerTest {

    private static final LocalDate SERVICE_DATE = LocalDate.of(2030, 5, 6); // 월요일

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

    /**
     * {@code Ruling 359} — 보호자를 아직 연결하지 않은 학생은 {@code guardian_phone} 이 {@code null}
     * 이다(§1.13 목록). {@code fx.student} 만 부르고 {@code fx.guardianWithPhone} 을 부르지 않아
     * "아직 연결하지 않은" 상태를 그대로 재현한다.
     *
     * <p>수정 전 관측(RED) — 정정 전 정본은 이 필드를 {@code ●}(항상 값 있음)로 적고 있었을 뿐, 서버
     * 코드({@link src.backend.monitoring.query.AdminRunRosterQueryService#guardianPhonesOf}) 는
     * 이미 미연결 학생을 맵에서 빠뜨려 {@code null} 을 그대로 냈다 — 이 시험이 없어 그 사실을 아무도
     * 고정해 두지 않았을 뿐이다. 이 시험을 지우면(변형) 실패로 드러난다.
     */
    @Test
    void 보호자가_연결되지_않은_학생은_guardian_phone_이_null_이다() throws Exception {
        Phase9RosterFixtures fx = fixtures();
        long academyId = fx.academyWithCoordinates();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.500000", "127.000000");
        fx.route(academyId, busId, Weekday.MON, Direction.TO_ACADEMY, stopId);
        long studentId = fx.student(academyId, "학생1");
        fx.verifiedAddress(studentId, stopId, Weekday.MON, Direction.TO_ACADEMY, "37.500000", "127.000000");
        OffsetDateTime departTime = SERVICE_DATE.atTime(8, 0).atOffset(java.time.ZoneOffset.of("+09:00"));
        long runId = fx.confirmedRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));

        mockMvc.perform(get("/api/v1/admin/runs/" + runId + "/roster").header("Authorization", 메인관리자_토큰()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stops[0].students[0].guardian_phone").value(nullValue()));
    }

    private String 메인관리자_토큰() {
        return "Bearer " + tokenProvider.createAccessToken(1L, null, Role.SYSTEM_ADMIN, AccountStatus.ACTIVE);
    }
}
