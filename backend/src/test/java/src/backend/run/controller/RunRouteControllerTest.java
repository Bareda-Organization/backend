package src.backend.run.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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
import src.backend.routing.entity.Waypoint;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.routing.repository.WaypointRepository;
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

    @Autowired
    private WaypointRepository waypointRepository;

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
        assertThat((Integer) JsonPath.read(body, "$.data.next_stop.stop_id"))
                .isEqualTo((int) 정차_항목_id(academyId, runId, stop2));
        assertThat((String) JsonPath.read(body, "$.data.skipped_notice")).isEqualTo("1번 정차지 결번 — 학생 하차 예정 없음");
    }

    @Test
    void 정상_조회는_stops_배열에_정차지_순서를_싣는다() throws Exception {
        // F1 결과 ②(R3 ⑪e) — 기존 결번 시험은 next_stop·skipped_notice 만 보고 stops[] 자체의
        // 값은 확인하지 않았다. 결번 없이 정차지 2곳을 그대로 태워 stops[0] 이 첫 정차지를 가리키고
        // 개수가 심은 만큼인지 본다.
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
        Phase9RosterFixtures.ManagerAccount manager = fx.manager(academyId, ManagerRole.DRIVER, "기사");
        fx.assign(runId, manager.managerId(), ManagerRole.DRIVER);

        // BR-002(Ruling 327) — 등원은 정차 목록 맨 뒤에 학원 항목이 있고, stop_id 는 run_stop.id 다.
        mockMvc.perform(get("/api/v1/runs/" + runId + "/route").header("Authorization",
                        토큰(manager.accountId(), academyId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stops[0].stop_id").value((int) 정차_항목_id(academyId, runId, stop1)))
                .andExpect(jsonPath("$.data.stops[0].is_destination").value(false))
                .andExpect(jsonPath("$.data.stops.length()").value(3))
                .andExpect(jsonPath("$.data.stops[2].is_destination").value(true))
                .andExpect(jsonPath("$.data.stops[2].name").value(RunConfirmationFixtures.ACADEMY_NAME))
                .andExpect(jsonPath("$.data.stops[2].lat").isNumber())
                .andExpect(jsonPath("$.data.stops[2].student_count").value(0));
    }

    @Test
    void BR_015_지나간_경유_지점은_next_stop_에_남지_않는다() throws Exception {
        // 경유 지점은 도착 처리 대상이 아니다 — 그 뒤 승하차지에 도착했으면 경유 지점도 지난 것이다.
        Phase9RosterFixtures fx = fixtures();
        long academyId = fx.academyWithCoordinates();
        long busId = fx.bus(academyId);
        long stop1 = fx.stop(academyId, "37.500000", "127.000000");
        fx.route(academyId, busId, Weekday.FRI, Direction.FROM_ACADEMY, stop1);
        long student1 = fx.student(academyId, "학생1");
        fx.verifiedAddress(student1, stop1, Weekday.FRI, Direction.FROM_ACADEMY, "37.500000", "127.000000");
        OffsetDateTime departTime = OffsetDateTime.parse("2031-07-04T16:00:00+09:00");
        long runId = fx.confirmedRun(academyId, busId, LocalDate.parse(SERVICE_DATE), Direction.FROM_ACADEMY,
                departTime, departTime.minusMinutes(30));
        Phase9RosterFixtures.ManagerAccount manager = fx.manager(academyId, ManagerRole.DRIVER, "기사");
        fx.assign(runId, manager.managerId(), ManagerRole.DRIVER);
        long stop2 = fx.stop(academyId, "37.510000", "127.010000");
        long stop3 = fx.stop(academyId, "37.520000", "127.020000");
        Long versionId = confirmedRouteRepository.findById(runId).map(ConfirmedRoute::getCurrentVersionId)
                .orElseThrow();
        Waypoint waypoint = waypointRepository.save(Waypoint.forRun(runId, "주유소", null,
                new BigDecimal("37.505000"), new BigDecimal("127.005000"), null, manager.managerId(),
                OffsetDateTime.now()));
        waypoint.apply();
        waypointRepository.save(waypoint);
        runStopRepository.save(RunStop.forWaypoint(versionId, waypoint.getId(), 2, null));
        runStopRepository.save(RunStop.forStop(versionId, stop2, 3, null));
        runStopRepository.save(RunStop.forStop(versionId, stop3, 4, null));
        도착_처리한다(academyId, runId, stop1);
        도착_처리한다(academyId, runId, stop2);

        MvcResult result = mockMvc
                .perform(get("/api/v1/runs/" + runId + "/route").header("Authorization",
                        토큰(manager.accountId(), academyId)))
                .andExpect(status().isOk())
                .andReturn();

        assertThat((Integer) JsonPath.read(본문(result), "$.data.next_stop.stop_id"))
                .as("stop2 에 도착했으면 그 앞 경유 지점은 지난 것이다 — 다음은 stop3")
                .isEqualTo((int) 정차_항목_id(academyId, runId, stop3));
    }

    @Test
    void 제거된_경유지는_건너뛰고_좌표_있는_다음_정차지가_next_stop_이다() throws Exception {
        // C-null 대상 2(§4.3 next_stop.lat·lng) 음성 대조용 — 배포 후 제거된 강제 경유지가 다음 차례로
        // 올라오면(RunRouteQueryService#hasResolvedTarget 이 없으면) next_stop 이 좌표 전부 null 인
        // 경유지 행을 그대로 내보낸다. 그 필터를 되돌리면 이 시험만 실패해야 한다.
        Phase9RosterFixtures fx = fixtures();
        long academyId = fx.academyWithCoordinates();
        long busId = fx.bus(academyId);
        long stop1 = fx.stop(academyId, "37.500000", "127.000000");
        fx.route(academyId, busId, Weekday.FRI, Direction.FROM_ACADEMY, stop1);
        long student1 = fx.student(academyId, "학생1");
        fx.verifiedAddress(student1, stop1, Weekday.FRI, Direction.FROM_ACADEMY, "37.500000", "127.000000");
        OffsetDateTime departTime = OffsetDateTime.parse("2031-07-04T08:00:00+09:00");
        long runId = fx.confirmedRun(academyId, busId, LocalDate.parse(SERVICE_DATE), Direction.FROM_ACADEMY,
                departTime, departTime.minusMinutes(30));
        Phase9RosterFixtures.ManagerAccount manager = fx.manager(academyId, ManagerRole.DRIVER, "기사");
        fx.assign(runId, manager.managerId(), ManagerRole.DRIVER);

        // 회차 확정 이후 별도로 배정·배포·제거되는 강제 경유지(RTE-10, §5.15) — 노선 설계 단계
        // (RouteStop)가 아니라 확정된 회차(RunStop)에 직접 붙는다는 도메인 형태를 그대로 따른다.
        long stop2 = fx.stop(academyId, "37.510000", "127.010000");
        Long versionId = confirmedRouteRepository.findById(runId).map(ConfirmedRoute::getCurrentVersionId)
                .orElseThrow();
        Waypoint waypoint = waypointRepository.save(Waypoint.forRun(runId, "임시집결지", null,
                new BigDecimal("37.505000"), new BigDecimal("127.005000"), null, manager.managerId(),
                OffsetDateTime.now()));
        waypoint.apply();
        waypoint.markRemoved(OffsetDateTime.now());
        waypointRepository.save(waypoint);
        runStopRepository.save(RunStop.forWaypoint(versionId, waypoint.getId(), 2, null));
        runStopRepository.save(RunStop.forStop(versionId, stop2, 3, null));
        도착_처리한다(academyId, runId, stop1);

        MvcResult result = mockMvc
                .perform(get("/api/v1/runs/" + runId + "/route").header("Authorization",
                        토큰(manager.accountId(), academyId)))
                .andExpect(status().isOk())
                .andReturn();

        String body = 본문(result);
        assertThat((Integer) JsonPath.read(body, "$.data.next_stop.stop_id"))
                .isEqualTo((int) 정차_항목_id(academyId, runId, stop2));
        assertThat((Double) JsonPath.read(body, "$.data.next_stop.lat")).isNotNull();
        assertThat((Double) JsonPath.read(body, "$.data.next_stop.lng")).isNotNull();
        // stops[] 전체 목록은 API_SPEC §1.13 의 "등급이 다른 것" carve-out 대상이라 제거된 경유지
        // 항목의 좌표 null 을 그대로 보존한다 — next_stop 만 걸러야 하는 것을 확인한다.
        assertThat((Integer) JsonPath.read(body, "$.data.stops.length()")).isEqualTo(3);
        assertThat((Object) JsonPath.read(body, "$.data.stops[1].lat")).isNull();
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

    /** 학생 승하차지 {@code stopId} 가 실린 정차 항목의 {@code run_stop.id} — §4.2·§4.3·§4.5 의 {@code stop_id} 값(Ruling 327). */
    private long 정차_항목_id(long academyId, long runId, long stopId) {
        Long versionId = confirmedRouteRepository.findById(runId).map(ConfirmedRoute::getCurrentVersionId)
                .orElseThrow();
        return runStopRepository.findAllByRouteVersionIdAndAcademyIdOrderBySeq(versionId, academyId).stream()
                .filter(stop -> Long.valueOf(stopId).equals(stop.getStopId()))
                .findFirst().orElseThrow().getId();
    }

    private void 도착_처리한다(long academyId, long runId, long stopId) {
        Long versionId = confirmedRouteRepository.findById(runId).map(ConfirmedRoute::getCurrentVersionId)
                .orElseThrow();
        List<RunStop> runStops = runStopRepository.findAllByRouteVersionIdAndAcademyIdOrderBySeq(versionId,
                academyId);
        RunStop target = runStops.stream().filter(stop -> stopId == (stop.getStopId() == null ? -1L : stop.getStopId()))
                .findFirst().orElseThrow();
        target.markArrived(OffsetDateTime.now());
        runStopRepository.save(target);
    }

    private String 토큰(long accountId, long academyId) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, Role.DRIVER, AccountStatus.ACTIVE);
    }

    private String 본문(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
