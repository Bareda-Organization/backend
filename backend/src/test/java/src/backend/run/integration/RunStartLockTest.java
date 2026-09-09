package src.backend.run.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
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
import src.backend.run.controller.DriverRunFixtures;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

/**
 * 목표 18(Phase 9 이월 ④, P9 목표 2) — 운행 시작이 노선을 실제로 잠그는가.
 *
 * <p>{@code ChangeRequestControllerTest} 의 목표9 시험은 이미 {@code moving} 회차에서 변경 신청이
 * {@code 403} 임을 검사하지만, 회차를 {@code jdbcTemplate.update(...)} 로 직접 {@code moving} 으로
 * 바꿔 만든다 — 즉 <b>실제 시작 경로가 잠그는지는 검사하지 않는다.</b> Phase 9 좌석은 그 시험으로
 * "기존 커버리지가 방어한다" 고 판단했으나 조율자가 채택하지 않았다(이월 사유) — 시작 처리
 * ({@code RunStartCommandService})와 잠금 판정({@code ChangeWindowPolicy})은 서로 다른 클래스라,
 * 시작 처리가 상태 전이를 빠뜨려도 위 시험은 여전히 통과한다.
 *
 * <p>그래서 이 시험은 <b>실제 {@code POST .../start} 호출로 전이시킨 뒤</b> 같은 회차에 변경 신청을
 * 넣는다 — 두 실제 HTTP 경로를 한 시험 안에서 잇는다는 점에서 {@code Phase9CrossSeatWiringTest} 와
 * 같은 종류의 시험이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RunStartLockTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private Clock clock;

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
    private src.backend.boarding.repository.RunRiderRepository runRiderRepository;

    @Autowired
    private AcademyStaffRepository academyStaffRepository;

    @Autowired
    private GuardianRepository guardianRepository;

    @Autowired
    private GuardianStudentRepository guardianStudentRepository;

    @Autowired
    private ChangeRequestRepository changeRequestRepository;

    @TestConfiguration
    static class FixedClockConfig {

        // Run.forSchedule 이 service_date 를 2030-04-01 로 고정해 두므로(DriverRunFixtures), 같은
        // 날짜 위에서 출발 ±10분 창을 결정론적으로 맞추려면 시계도 그 날짜 위에 고정해야 한다.
        private static final Instant FIXED = Instant.parse("2030-04-01T03:00:00Z"); // 2030-04-01 12:00 KST

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED, ZoneId.of("Asia/Seoul"));
        }
    }

    private DriverRunFixtures fixtures() {
        return new DriverRunFixtures(academyRepository, busRepository, stopRepository, studentRepository,
                accountRepository, managerRepository, assignmentRepository, runRepository, confirmedRouteRepository,
                routeVersionRepository, runStopRepository, runRiderRepository, academyStaffRepository,
                guardianRepository, guardianStudentRepository, changeRequestRepository);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    @Test
    @DisplayName("목표18 — 실제 시작 호출로 moving 이 된 회차는, 그 직후 학부모의 변경 신청이 403 CHANGE_WINDOW_CLOSED 다")
    void 실제_시작_직후_변경_신청이_잠긴다() throws Exception {
        long academyId = fixtures().academy();
        long busId = fixtures().bus(academyId);
        long studentId = fixtures().student(academyId, "학생1");
        long parentAccountId = fixtures().guardianOf(academyId, studentId, "학부모", now());

        // 출발 5분 전 — RunStartWindowPolicy 의 ±10분 창 안이라 실제 시작이 성공해야 한다.
        OffsetDateTime departTime = now().plusMinutes(5);
        long runId = fixtures().confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        long driverAccountId = fixtures().assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());

        // 실제 시작 경로 — jdbcTemplate 로 상태를 직접 바꾸지 않는다.
        mockMvc.perform(post("/api/v1/runs/" + runId + "/start")
                        .header("Authorization", 기사_토큰(driverAccountId, academyId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.run_status").value("moving"));

        String status = jdbcTemplate.queryForObject("SELECT status FROM run WHERE id = ?", String.class, runId);
        org.assertj.core.api.Assertions.assertThat(status)
                .as("실제 시작 호출이 DB 상태를 moving 으로 전이시켜야 이 시험의 나머지가 유효하다")
                .isEqualTo("moving");

        // 같은 회차에 학부모가 변경 신청 — ③구간이므로 타입과 무관하게 잠겨야 한다(목표9와 같은 판정
        // 지점을 겨누되, 그 판정 지점 앞의 상태가 실제 시작 호출로 만들어졌다는 점이 다르다).
        mockMvc.perform(post("/api/v1/students/" + studentId + "/change-requests")
                        .header("Authorization", 학부모_토큰(parentAccountId, academyId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"cancel","run_id":%d,"new_address":null,"reason":"사정상 결석"}"""
                                .formatted(runId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CHANGE_WINDOW_CLOSED"));
    }

    private String 기사_토큰(long accountId, long academyId) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, Role.DRIVER, AccountStatus.ACTIVE);
    }

    private String 학부모_토큰(long accountId, long academyId) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, Role.PARENT, AccountStatus.ACTIVE);
    }
}
