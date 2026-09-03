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
import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
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
 * §5.4 {@code GET /staff/runs/{runId}/roster} — RST-03·A-04, Phase 9 목표 6.
 *
 * <p>{@link RunRosterControllerTest}(§4.2)와 정확히 반대되는 두 지점을 본다 — 보호자 연락처가
 * <b>원문 그대로</b> 내려가는지, 결석 학생이 <b>행으로 남아</b> {@code status=absent} 로 표시되는지.
 * 확정 전(idle) 회차도 매니저 앱과 달리 막히지 않는다 — 학원 관계자는 배치와 무관하게 그 학원
 * 회차 전체를 볼 권한을 이미 가졌다({@code STUDENT_READ_SENSITIVE}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StaffRosterControllerTest {

    private static final String SERVICE_DATE = "2031-07-03";

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
    void 보호자_연락처는_원문_그대로_내려간다() throws Exception {
        Phase9RosterFixtures fx = fixtures();
        long academyId = fx.academyWithCoordinates();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.500000", "127.000000");
        fx.route(academyId, busId, Weekday.THU, Direction.TO_ACADEMY, stopId);
        long studentId = fx.student(academyId, "학생1");
        fx.verifiedAddress(studentId, stopId, Weekday.THU, Direction.TO_ACADEMY, "37.500000", "127.000000");
        fx.guardianWithPhone(academyId, studentId, "010-2345-8814");
        OffsetDateTime departTime = OffsetDateTime.parse("2031-07-03T08:00:00+09:00");
        long runId = fx.confirmedRun(academyId, busId, LocalDate.parse(SERVICE_DATE), Direction.TO_ACADEMY,
                departTime, departTime.minusMinutes(30));
        long staffAccountId = 관계자_계정을_만든다(academyId);

        MvcResult result = mockMvc
                .perform(get("/api/v1/staff/runs/" + runId + "/roster").header("Authorization",
                        토큰(staffAccountId, academyId)))
                .andExpect(status().isOk())
                .andReturn();

        assertThat((String) JsonPath.read(본문(result), "$.data[0].guardian_phone")).isEqualTo("010-2345-8814");
    }

    @Test
    void 결석_학생도_행으로_남고_상태만_absent_다() throws Exception {
        Phase9RosterFixtures fx = fixtures();
        long academyId = fx.academyWithCoordinates();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.500000", "127.000000");
        fx.route(academyId, busId, Weekday.THU, Direction.TO_ACADEMY, stopId);
        long studentId = fx.student(academyId, "학생1");
        fx.verifiedAddress(studentId, stopId, Weekday.THU, Direction.TO_ACADEMY, "37.500000", "127.000000");
        OffsetDateTime departTime = OffsetDateTime.parse("2031-07-03T08:00:00+09:00");
        long runId = fx.confirmedRun(academyId, busId, LocalDate.parse(SERVICE_DATE), Direction.TO_ACADEMY,
                departTime, departTime.minusMinutes(30));
        결석_처리한다(academyId, runId, studentId);
        long staffAccountId = 관계자_계정을_만든다(academyId);

        MvcResult result = mockMvc
                .perform(get("/api/v1/staff/runs/" + runId + "/roster").header("Authorization",
                        토큰(staffAccountId, academyId)))
                .andExpect(status().isOk())
                .andReturn();

        String body = 본문(result);
        List<?> items = JsonPath.read(body, "$.data");
        assertThat(items).hasSize(1);
        assertThat((String) JsonPath.read(body, "$.data[0].status")).isEqualTo("absent");
    }

    @Test
    void 확정_전_회차도_조회할_수_있다() throws Exception {
        Phase9RosterFixtures fx = fixtures();
        long academyId = fx.academyWithCoordinates();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.500000", "127.000000");
        fx.route(academyId, busId, Weekday.THU, Direction.TO_ACADEMY, stopId);
        OffsetDateTime departTime = OffsetDateTime.parse("2031-07-03T08:00:00+09:00");
        long runId = fx.idleRun(academyId, busId, LocalDate.parse(SERVICE_DATE), Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        long staffAccountId = 관계자_계정을_만든다(academyId);

        mockMvc.perform(get("/api/v1/staff/runs/" + runId + "/roster").header("Authorization",
                토큰(staffAccountId, academyId)))
                .andExpect(status().isOk());
    }

    /**
     * API_SPEC §1.5, Ruling 239, 2026-09-03 사용자 판정 ② — 존재하는 회차라도 요청자 소속 학원과
     * 다르면 {@code 403 ACADEMY_SCOPE_VIOLATION}(옛 동작은 조회 조건에 학원 id 를 섞어 404 로 뭉갰다).
     */
    @Test
    void 타_학원_회차_명단_조회는_403_이다() throws Exception {
        Phase9RosterFixtures fx = fixtures();
        long myAcademyId = fx.academyWithCoordinates();
        long staffAccountId = 관계자_계정을_만든다(myAcademyId);

        long otherAcademyId = fx.academyWithCoordinates();
        long otherBusId = fx.bus(otherAcademyId);
        long otherStopId = fx.stop(otherAcademyId, "37.500000", "127.000000");
        fx.route(otherAcademyId, otherBusId, Weekday.THU, Direction.TO_ACADEMY, otherStopId);
        OffsetDateTime departTime = OffsetDateTime.parse("2031-07-03T08:00:00+09:00");
        long otherRunId = fx.confirmedRun(otherAcademyId, otherBusId, LocalDate.parse(SERVICE_DATE),
                Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30));

        mockMvc.perform(get("/api/v1/staff/runs/" + otherRunId + "/roster").header("Authorization",
                토큰(staffAccountId, myAcademyId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACADEMY_SCOPE_VIOLATION"));
    }

    /** 존재하지 않는 회차 id 는 학원 범위와 무관하게 {@code 404 RUN_NOT_FOUND} 그대로다. */
    @Test
    void 존재하지_않는_회차는_404_다() throws Exception {
        Phase9RosterFixtures fx = fixtures();
        long academyId = fx.academyWithCoordinates();
        long staffAccountId = 관계자_계정을_만든다(academyId);

        mockMvc.perform(get("/api/v1/staff/runs/999999999/roster").header("Authorization",
                토큰(staffAccountId, academyId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_FOUND"));
    }

    private long 관계자_계정을_만든다(long academyId) {
        Account account = accountRepository.save(Account.forSignup(academyId,
                "p9staff" + System.nanoTime(), "{noop}password", "관계자", "010-0000-0000", null, Role.STAFF));
        return account.getId();
    }

    private void 결석_처리한다(long academyId, long runId, long studentId) {
        List<RunRider> riders = runRiderRepository.findAllByRunIdAndAcademyId(runId, academyId);
        RunRider target = riders.stream().filter(rider -> rider.getStudentId() == studentId).findFirst()
                .orElseThrow();
        target.markAbsent(OffsetDateTime.now());
        runRiderRepository.save(target);
    }

    private String 토큰(long accountId, long academyId) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, Role.STAFF, AccountStatus.ACTIVE);
    }

    private String 본문(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
