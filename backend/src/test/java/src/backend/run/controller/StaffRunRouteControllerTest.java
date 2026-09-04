package src.backend.run.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

/**
 * §5.19 {@code GET /staff/runs/{runId}/route} — RTE-02, F1 S3 목표 11.
 *
 * <p>매니저용 {@code RunRouteControllerTest}(§4.3)와 같은 계열이지만 이쪽은 학원 범위·확정 버전
 * 존재를 축으로 삼는다 — 정차 조립 자체({@code next_stop} 건너뛰기 등)는
 * {@link src.backend.run.query.RunRouteQueryService#buildFromVersion} 을 공유해 이미 §4.3 시험이
 * 검증하므로 여기서 다시 재현하지 않는다(같은 판정을 두 벌 만들지 않는다, 과업 지시서 판단 근거).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StaffRunRouteControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private BusRepository busRepository;

    @Autowired
    private StopRepository stopRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ManagerRepository managerRepository;

    @Autowired
    private AssignmentRepository assignmentRepository;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private ConfirmedRouteRepository confirmedRouteRepository;

    @Autowired
    private RouteVersionRepository routeVersionRepository;

    @Autowired
    private RunStopRepository runStopRepository;

    @Autowired
    private RunRiderRepository runRiderRepository;

    @Autowired
    private AcademyStaffRepository academyStaffRepository;

    @Autowired
    private GuardianRepository guardianRepository;

    @Autowired
    private GuardianStudentRepository guardianStudentRepository;

    @Autowired
    private ChangeRequestRepository changeRequestRepository;

    private DriverRunFixtures fixtures() {
        return new DriverRunFixtures(academyRepository, busRepository, stopRepository, studentRepository,
                accountRepository, managerRepository, assignmentRepository, runRepository, confirmedRouteRepository,
                routeVersionRepository, runStopRepository, runRiderRepository, academyStaffRepository,
                guardianRepository, guardianStudentRepository, changeRequestRepository);
    }

    @Test
    @DisplayName("목표11 — 200: §4.3 정차 구조 + route_version·published_at·ack 전부 채워진다")
    void 확정_노선을_route_version과_ack과_함께_돌려준다() throws Exception {
        DriverRunFixtures fx = fixtures();
        long academyId = fx.academy();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.500000", "127.000000");
        OffsetDateTime departTime = OffsetDateTime.now().plusHours(1);
        long runId = fx.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30));
        long versionId = fx.confirmedRouteWithVersion(runId, departTime.minusMinutes(40));
        fx.runStopForStop(versionId, stopId, 1, departTime);
        long driverAccountId = fx.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사1", OffsetDateTime.now());
        fx.assignedManager(academyId, runId, ManagerRole.ESCORT, "동승자1", OffsetDateTime.now());
        long staffAccountId = fx.staffAccount(academyId, "관계자1");

        // 기사만 확인 — ack.driver=true·ack.escort=false 비대칭을 실측한다(StaffDashboardControllerTest
        // 목표1 과 같은 방식, 손으로 assignment 행을 UPDATE 하지 않는다).
        mockMvc.perform(post("/api/v1/runs/" + runId + "/ack-changes")
                        .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isOk());

        MvcResult result = mockMvc
                .perform(get("/api/v1/staff/runs/" + runId + "/route")
                        .header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stops[0].stop_id").value((int) stopId))
                .andExpect(jsonPath("$.data.route_version").value(1))
                .andExpect(jsonPath("$.data.published_at").exists())
                .andExpect(jsonPath("$.data.ack.driver").value(true))
                .andExpect(jsonPath("$.data.ack.escort").value(false))
                .andReturn();

        assertThat((Integer) JsonPath.read(본문(result), "$.data.route_version")).isEqualTo(1);
    }

    @Test
    @DisplayName("목표11 — ack 비대칭의 반대 방향: 동승자만 확인하면 driver=false·escort=true 다")
    void ack은_기사와_동승자를_각자_실제로_읽는다() throws Exception {
        // 위 200 시험은 기사만 확인해 ack.driver=true·ack.escort=false 만 실측한다 — 이 시험이 반대
        // 방향(동승자만 확인)을 채워야 "ack.driver 를 항상 true 로 고정" 같은 결함이 실제로 걸린다.
        DriverRunFixtures fx = fixtures();
        long academyId = fx.academy();
        long busId = fx.bus(academyId);
        OffsetDateTime departTime = OffsetDateTime.now().plusHours(1);
        long runId = fx.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30));
        fx.confirmedRouteWithVersion(runId, departTime.minusMinutes(40));
        fx.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사1", OffsetDateTime.now());
        long escortAccountId = fx.assignedManager(academyId, runId, ManagerRole.ESCORT, "동승자1",
                OffsetDateTime.now());
        long staffAccountId = fx.staffAccount(academyId, "관계자1");

        mockMvc.perform(post("/api/v1/runs/" + runId + "/ack-changes")
                        .header("Authorization", 토큰(escortAccountId, academyId, Role.ESCORT)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/staff/runs/" + runId + "/route")
                        .header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ack.driver").value(false))
                .andExpect(jsonPath("$.data.ack.escort").value(true));
    }

    @Test
    @DisplayName("목표11 — 409: 확정 노선(route_version) 미배포는 RUN_NOT_CONFIRMED 다")
    void 확정_노선이_없으면_409_RUN_NOT_CONFIRMED_다() throws Exception {
        DriverRunFixtures fx = fixtures();
        long academyId = fx.academy();
        long busId = fx.bus(academyId);
        OffsetDateTime departTime = OffsetDateTime.now().plusHours(1);
        // confirmed 상태(run.status)지만 confirmedRouteWithVersion 을 호출하지 않아 route_version 이
        // 아직 없다 — 매니저용 409(§4.3, run.status=idle)와 발생 조건이 다르다(과업 지시서 판단 근거).
        long runId = fx.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30));
        long staffAccountId = fx.staffAccount(academyId, "관계자1");

        mockMvc.perform(get("/api/v1/staff/runs/" + runId + "/route")
                        .header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_CONFIRMED"));
    }

    @Test
    @DisplayName("목표11 — 404: 존재하지 않는 회차")
    void 존재하지_않는_회차는_404_RUN_NOT_FOUND_다() throws Exception {
        DriverRunFixtures fx = fixtures();
        long academyId = fx.academy();
        long staffAccountId = fx.staffAccount(academyId, "관계자1");

        mockMvc.perform(get("/api/v1/staff/runs/999999999/route")
                        .header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_FOUND"));
    }

    @Test
    @DisplayName("목표11 — 403: 타 학원 관계자는 ACADEMY_SCOPE_VIOLATION")
    void 타_학원_관계자는_403_ACADEMY_SCOPE_VIOLATION_이다() throws Exception {
        DriverRunFixtures fx = fixtures();
        long myAcademyId = fx.academy();
        long myBusId = fx.bus(myAcademyId);
        OffsetDateTime departTime = OffsetDateTime.now().plusHours(1);
        long runId = fx.confirmedRun(myAcademyId, myBusId, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        fx.confirmedRouteWithVersion(runId, departTime.minusMinutes(40));

        long otherAcademyId = fx.academy();
        long otherStaffAccountId = fx.staffAccount(otherAcademyId, "관계자2");

        mockMvc.perform(get("/api/v1/staff/runs/" + runId + "/route")
                        .header("Authorization", 토큰(otherStaffAccountId, otherAcademyId, Role.STAFF)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACADEMY_SCOPE_VIOLATION"));
    }

    @Test
    @DisplayName("목표11 — 403: 매니저(기사) 토큰은 관계자 전용 엔드포인트에 진입 불가")
    void 매니저_토큰은_403_이다() throws Exception {
        DriverRunFixtures fx = fixtures();
        long academyId = fx.academy();
        long busId = fx.bus(academyId);
        OffsetDateTime departTime = OffsetDateTime.now().plusHours(1);
        long runId = fx.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30));
        fx.confirmedRouteWithVersion(runId, departTime.minusMinutes(40));
        long driverAccountId = fx.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사1", OffsetDateTime.now());

        mockMvc.perform(get("/api/v1/staff/runs/" + runId + "/route")
                        .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    private String 토큰(long accountId, long academyId, Role role) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, role, AccountStatus.ACTIVE);
    }

    private String 본문(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
