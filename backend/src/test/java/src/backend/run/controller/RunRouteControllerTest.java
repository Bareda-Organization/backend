package src.backend.run.controller;

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
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.common.enums.Weekday;
import src.backend.global.security.JwtTokenProvider;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.entity.RunStop;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.routing.repository.RunStopRepository;
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
 * §4.3 {@code GET /runs/{runId}/route} — RUN-03·M-08·M-09·Ruling 205, Phase 9 목표 8.
 *
 * <p>이 클래스의 축은 <b>{@code next_stop} 이 결번(SKIPPED) 정차지를 건너뛰고 그 다음 실제
 * 정차지를 가리키는가</b>다(목표 8) — {@code skipped_notice} 가 그 결번 사유를 함께 실어야
 * "건너뛴 이유를 모르는 다음 정차지 안내" 가 되지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RunRouteControllerTest {

    private static final String SERVICE_DATE = "2031-07-04";

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
    private ConfirmedRouteRepository confirmedRouteRepository;

    @Autowired
    private RunStopRepository runStopRepository;

    private Phase9RosterFixtures fixtures() {
        RunConfirmationFixtures base = new RunConfirmationFixtures(academyRepository, busRepository, routeRepository,
                routeStopRepository, stopRepository, studentRepository, weeklyAddressRepository, runRepository);
        return new Phase9RosterFixtures(base, managerRepository, accountRepository, assignmentRepository,
                guardianRepository, guardianStudentRepository, confirmationService);
    }

    @Test
    void 결번_정차지는_건너뛰고_다음_실제_정차지가_next_stop_이다() throws Exception {
        Phase9RosterFixtures fx = fixtures();
        long academyId = fx.academyWithCoordinates();
        long busId = fx.bus(academyId);
        long stop1 = fx.stop(academyId, "37.500000", "127.000000");
        long stop2 = fx.stop(academyId, "37.510000", "127.010000");
        fx.route(academyId, busId, Weekday.FRI, Direction.TO_ACADEMY, stop1, stop2);
        long student1 = fx.student(academyId, "학생1");
        long student2 = fx.student(academyId, "학생2");
        fx.verifiedAddress(student1, stop1, Weekday.FRI, Direction.TO_ACADEMY, "37.500000", "127.000000");
        fx.verifiedAddress(student2, stop2, Weekday.FRI, Direction.TO_ACADEMY, "37.510000", "127.010000");
        OffsetDateTime departTime = OffsetDateTime.parse("2031-07-04T08:00:00+09:00");
        long runId = fx.confirmedRun(academyId, busId, LocalDate.parse(SERVICE_DATE), Direction.TO_ACADEMY,
                departTime, departTime.minusMinutes(30));
        결번_처리한다(academyId, runId, stop1, "1번 정차지 결번 — 학생 하차 예정 없음");
        Phase9RosterFixtures.ManagerAccount manager = fx.manager(academyId, ManagerRole.DRIVER, "기사");
        fx.assign(runId, manager.managerId(), ManagerRole.DRIVER);

        MvcResult result = mockMvc
                .perform(get("/api/v1/runs/" + runId + "/route").header("Authorization",
                        토큰(manager.accountId(), academyId)))
                .andExpect(status().isOk())
                .andReturn();

        String body = 본문(result);
        assertThat((Integer) JsonPath.read(body, "$.data.next_stop.stop_id")).isEqualTo((int) stop2);
        assertThat((String) JsonPath.read(body, "$.data.skipped_notice")).isEqualTo("1번 정차지 결번 — 학생 하차 예정 없음");
    }

    @Test
    void 확정_전_회차는_409_RUN_NOT_CONFIRMED_다() throws Exception {
        Phase9RosterFixtures fx = fixtures();
        long academyId = fx.academyWithCoordinates();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.500000", "127.000000");
        fx.route(academyId, busId, Weekday.FRI, Direction.TO_ACADEMY, stopId);
        OffsetDateTime departTime = OffsetDateTime.parse("2031-07-04T08:00:00+09:00");
        long runId = fx.idleRun(academyId, busId, LocalDate.parse(SERVICE_DATE), Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        Phase9RosterFixtures.ManagerAccount manager = fx.manager(academyId, ManagerRole.DRIVER, "기사");
        fx.assign(runId, manager.managerId(), ManagerRole.DRIVER);

        mockMvc.perform(get("/api/v1/runs/" + runId + "/route").header("Authorization",
                토큰(manager.accountId(), academyId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_CONFIRMED"));
    }

    private void 결번_처리한다(long academyId, long runId, long stopId, String notice) {
        Long versionId = confirmedRouteRepository.findById(runId).map(ConfirmedRoute::getCurrentVersionId)
                .orElseThrow();
        List<RunStop> runStops = runStopRepository.findAllByRouteVersionIdAndAcademyIdOrderBySeq(versionId,
                academyId);
        RunStop target = runStops.stream().filter(stop -> stopId == (stop.getStopId() == null ? -1L : stop.getStopId()))
                .findFirst().orElseThrow();
        target.markSkipped(notice);
        runStopRepository.save(target);
    }

    private String 토큰(long accountId, long academyId) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, Role.DRIVER, AccountStatus.ACTIVE);
    }

    private String 본문(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
