package src.backend.monitoring.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

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
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import testsupport.redis.RedisTestContainerBase;

/**
 * 메인 관리자 콘솔의 전체 관제(§6.8)·회차 명단(§6.9) API 컨트롤러 시험(Phase 13 T2 목표 5(관리자
 * 쪽)·8·9·10·11). {@link AdminMonitoringFixtures} 로 매번 새 학원·회차를 만들어 시드 데이터와
 * 겹치지 않는다 — {@code StudentBusPositionControllerTest} 의 시드 의존과 다른 이유는, 이 컨트롤러가
 * 여러 학원을 넘나드는 격리 시험(목표 9)을 요구해 학원 2곳을 매번 새로 만드는 편이 더 좁기 때문이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminMonitoringControllerTest extends RedisTestContainerBase {

    private static final String LIVE = "/api/v1/admin/academies/%d/runs/live";

    private static final String ROSTER = "/api/v1/admin/runs/%d/roster";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private Clock clock;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private AcademyStaffRepository academyStaffRepository;

    @Autowired
    private BusRepository busRepository;

    @Autowired
    private StopRepository stopRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private GuardianRepository guardianRepository;

    @Autowired
    private GuardianStudentRepository guardianStudentRepository;

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
    private ManagerRepository managerRepository;

    @Autowired
    private AssignmentRepository assignmentRepository;

    @TestConfiguration
    static class FixedClockConfig {

        private static final Instant FIXED = Instant.parse("2030-04-01T03:00:00Z"); // 2030-04-01 12:00 KST

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED, ZoneId.of("Asia/Seoul"));
        }
    }

    private AdminMonitoringFixtures fixtures() {
        return new AdminMonitoringFixtures(academyRepository, academyStaffRepository, busRepository, stopRepository,
                studentRepository, accountRepository, guardianRepository, guardianStudentRepository, runRepository,
                confirmedRouteRepository, routeVersionRepository, runStopRepository, runRiderRepository,
                managerRepository, assignmentRepository);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    // ── 목표 8·9 — GET /admin/academies/{id}/runs/live ─────────────────────

    /**
     * §6.8 필드 전부를 항목 단위로 대조한다(목표 8) — position(신선한 위치) · depart_time ·
     * est_depart_time(started_at) · stops[](미도착 정차의 eta 값) · destination_eta
     * (depart_time+est_duration_min) · driver/escort 원문 연락처.
     */
    @Test
    void 학원_1곳의_운행중_회차_실시간_관제_필드_전부가_정본과_일치한다() throws Exception {
        AdminMonitoringFixtures f = fixtures();
        long academyId = f.academy();
        long busId = f.bus(academyId);
        long stopId = f.stop(academyId, "37.500000", "127.030000");

        OffsetDateTime departTime = now().minusMinutes(20);
        OffsetDateTime startedAt = now().minusMinutes(15);
        int estDurationMin = 40;
        long runId = f.movingRun(academyId, busId, Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30),
                startedAt, estDurationMin);
        long versionId = f.confirmedRouteWithVersion(runId, now().minusMinutes(25));
        OffsetDateTime eta = now().plusMinutes(5);
        f.runStopForStop(versionId, stopId, 1, eta);

        AdminMonitoringFixtures.ManagerAccount driver = f.manager(academyId, ManagerRole.DRIVER, "박정우");
        AdminMonitoringFixtures.ManagerAccount escort = f.manager(academyId, ManagerRole.ESCORT, "최유나");
        f.assign(runId, driver.managerId(), ManagerRole.DRIVER);
        f.assign(runId, escort.managerId(), ManagerRole.ESCORT);

        OffsetDateTime received = now().minusSeconds(30);
        위치를_기록한다(runId, received);

        long adminAccountId = f.systemAdminAccount("메인관리자");

        MvcResult result = mockMvc.perform(get(LIVE.formatted(academyId)).header("Authorization",
                        메인관리자_토큰(adminAccountId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runs[0].run_id").value(runId))
                .andExpect(jsonPath("$.data.runs[0].run_status").value("moving"))
                .andExpect(jsonPath("$.data.runs[0].direction").value("to_academy"))
                .andExpect(jsonPath("$.data.runs[0].position.lat").value(37.400000))
                .andExpect(jsonPath("$.data.runs[0].position.received_at").exists())
                .andExpect(jsonPath("$.data.runs[0].depart_time").exists())
                .andExpect(jsonPath("$.data.runs[0].est_depart_time").exists())
                .andExpect(jsonPath("$.data.runs[0].stops[0].stop_id").value(stopId))
                .andExpect(jsonPath("$.data.runs[0].stops[0].eta").exists())
                .andExpect(jsonPath("$.data.runs[0].destination_eta").exists())
                .andExpect(jsonPath("$.data.runs[0].driver.name").value("박정우"))
                .andExpect(jsonPath("$.data.runs[0].driver.phone").value(driver.phone()))
                .andExpect(jsonPath("$.data.runs[0].escort.name").value("최유나"))
                .andExpect(jsonPath("$.data.runs[0].escort.phone").value(escort.phone()))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        String departTimeStr = JsonPath.read(body, "$.data.runs[0].depart_time");
        String estDepartTimeStr = JsonPath.read(body, "$.data.runs[0].est_depart_time");
        String stopEtaStr = JsonPath.read(body, "$.data.runs[0].stops[0].eta");
        String destinationEtaStr = JsonPath.read(body, "$.data.runs[0].destination_eta");

        assertThat(OffsetDateTime.parse(departTimeStr)).as("depart_time 은 회차 출발 예정 그대로")
                .isEqualTo(departTime);
        assertThat(OffsetDateTime.parse(estDepartTimeStr)).as("est_depart_time 은 run.started_at(Ruling 232)")
                .isEqualTo(startedAt);
        assertThat(OffsetDateTime.parse(stopEtaStr)).as("stops[].eta 는 run_stop.eta 저장값 그대로(재계산 부재)")
                .isEqualTo(eta);
        assertThat(OffsetDateTime.parse(destinationEtaStr))
                .as("destination_eta 는 depart_time + est_duration_min(Ruling 232) — 소요를 더하지 않으면 이 단언이 문다")
                .isEqualTo(departTime.plusMinutes(estDurationMin));
    }

    /**
     * {@code est_duration_min} 이 없으면 {@code destination_eta} 가 응답에서 비어야 한다(목표 6) —
     * {@code AdminAcademyLiveQueryService#destinationEtaOf} 자바독이 Ruling 232 근거로 이미 이 분기를
     * 서술하고 있으나(edge case) 그 분기를 직접 태우는 시험이 없었다.
     */
    @Test
    void est_duration_min_이_없으면_destination_eta_가_비어있다() throws Exception {
        AdminMonitoringFixtures f = fixtures();
        long academyId = f.academy();
        long busId = f.bus(academyId);
        OffsetDateTime departTime = now().minusMinutes(20);
        long runId = f.movingRun(academyId, busId, Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30),
                now().minusMinutes(15), null);

        long adminAccountId = f.systemAdminAccount("메인관리자");

        mockMvc.perform(get(LIVE.formatted(academyId)).header("Authorization", 메인관리자_토큰(adminAccountId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runs[0].run_id").value(runId))
                .andExpect(jsonPath("$.data.runs[0].destination_eta").doesNotExist());
    }

    /** 도착 처리된 정차는 {@code eta} 가 {@code null} 이다(목표 8 뒷항). */
    @Test
    void 도착_처리된_정차는_eta가_null이다() throws Exception {
        AdminMonitoringFixtures f = fixtures();
        long academyId = f.academy();
        long busId = f.bus(academyId);
        long stopId = f.stop(academyId, "37.500000", "127.030000");

        OffsetDateTime departTime = now().minusMinutes(20);
        long runId = f.movingRun(academyId, busId, Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30),
                now().minusMinutes(15), 40);
        long versionId = f.confirmedRouteWithVersion(runId, now().minusMinutes(25));
        long runStopId = f.runStopForStop(versionId, stopId, 1, now().plusMinutes(5));
        f.markArrived(runStopId, now().minusMinutes(1));

        long adminAccountId = f.systemAdminAccount("메인관리자");

        mockMvc.perform(get(LIVE.formatted(academyId)).header("Authorization", 메인관리자_토큰(adminAccountId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runs[0].stops[0].arrived_at").exists())
                .andExpect(jsonPath("$.data.runs[0].stops[0].eta").doesNotExist());
    }

    /** {@code moving} 이 아닌 회차는 관제 목록에 나오지 않는다(목표 8 — moving 필터). */
    @Test
    void 운행중이_아닌_회차는_목록에_나오지_않는다() throws Exception {
        AdminMonitoringFixtures f = fixtures();
        long academyId = f.academy();
        long busId = f.bus(academyId);
        OffsetDateTime departTime = now().plusMinutes(20);
        f.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30));

        long adminAccountId = f.systemAdminAccount("메인관리자");

        mockMvc.perform(get(LIVE.formatted(academyId)).header("Authorization", 메인관리자_토큰(adminAccountId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runs").isEmpty());
    }

    /** 메인 관리자는 어느 학원이든 조회할 수 있다 + 지정 학원의 회차만 나온다(목표 9). */
    @Test
    void 메인_관리자는_다른_학원의_관제도_조회하되_그_학원_회차만_나온다() throws Exception {
        AdminMonitoringFixtures f = fixtures();
        long academyA = f.academy();
        long academyB = f.academy();
        long busA = f.bus(academyA);
        long busB = f.bus(academyB);
        OffsetDateTime departTime = now().minusMinutes(20);
        long runA = f.movingRun(academyA, busA, Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30),
                now().minusMinutes(15), 30);
        f.movingRun(academyB, busB, Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30),
                now().minusMinutes(15), 30);

        long adminAccountId = f.systemAdminAccount("메인관리자");

        mockMvc.perform(get(LIVE.formatted(academyA)).header("Authorization", 메인관리자_토큰(adminAccountId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runs.length()").value(1))
                .andExpect(jsonPath("$.data.runs[0].run_id").value(runA));
    }

    /** 없는 학원은 {@code 404 ACADEMY_NOT_FOUND}(목표 9). */
    @Test
    void 없는_학원의_관제_조회는_404다() throws Exception {
        long adminAccountId = fixtures().systemAdminAccount("메인관리자");

        mockMvc.perform(get(LIVE.formatted(999_999_999L)).header("Authorization", 메인관리자_토큰(adminAccountId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ACADEMY_NOT_FOUND"));
    }

    // ── 목표 10·11 — GET /admin/runs/{runId}/roster ────────────────────────

    /** stops[]→students[] 사진·연락처가 원문 그대로 나온다(목표 10 앞항). */
    @Test
    void 회차_명단은_학생_사진과_연락처를_원문으로_담는다() throws Exception {
        AdminMonitoringFixtures f = fixtures();
        long academyId = f.academy();
        long busId = f.bus(academyId);
        long stopId = f.stop(academyId, "37.500000", "127.030000");
        OffsetDateTime departTime = now().minusMinutes(20);
        long runId = f.movingRun(academyId, busId, Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30),
                now().minusMinutes(15), 30);
        long versionId = f.confirmedRouteWithVersion(runId, now().minusMinutes(25));
        f.runStopForStop(versionId, stopId, 1, now().plusMinutes(5));

        long studentId = f.student(academyId, "김학생", "010-2311-8814", "https://example.com/photo.jpg");
        f.rider(runId, studentId, stopId);
        f.guardianOf(academyId, studentId, "010-5522-1043");

        long adminAccountId = f.systemAdminAccount("메인관리자");

        mockMvc.perform(get(ROSTER.formatted(runId)).header("Authorization", 메인관리자_토큰(adminAccountId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stops[0].stop_id").value(stopId))
                .andExpect(jsonPath("$.data.stops[0].students[0].student_id").value(studentId))
                .andExpect(jsonPath("$.data.stops[0].students[0].name").value("김학생"))
                .andExpect(jsonPath("$.data.stops[0].students[0].photo_url").value("https://example.com/photo.jpg"))
                .andExpect(jsonPath("$.data.stops[0].students[0].student_phone").value("010-2311-8814"))
                .andExpect(jsonPath("$.data.stops[0].students[0].guardian_phone").value("010-5522-1043"))
                .andExpect(jsonPath("$.data.stops[0].students[0].status").value("waiting"));
    }

    /** 없는 회차는 {@code 404 RUN_NOT_FOUND}(목표 11). */
    @Test
    void 없는_회차의_명단_조회는_404다() throws Exception {
        long adminAccountId = fixtures().systemAdminAccount("메인관리자");

        mockMvc.perform(get(ROSTER.formatted(999_999_999L)).header("Authorization", 메인관리자_토큰(adminAccountId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_FOUND"));
    }

    // ── 목표 5(관리자 쪽) — 학원 관계자는 403 ───────────────────────────────

    @Test
    void 학원_관계자는_전체_관제_콘솔을_호출할_수_없다() throws Exception {
        AdminMonitoringFixtures f = fixtures();
        long academyId = f.academy();
        long staffAccountId = f.staffAccount(academyId, "직원");

        mockMvc.perform(get(LIVE.formatted(academyId)).header("Authorization", 토큰(staffAccountId, academyId,
                        Role.STAFF)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 학원_관계자는_회차_명단_콘솔을_호출할_수_없다() throws Exception {
        AdminMonitoringFixtures f = fixtures();
        long academyId = f.academy();
        long busId = f.bus(academyId);
        OffsetDateTime departTime = now().minusMinutes(20);
        long runId = f.movingRun(academyId, busId, Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30),
                now().minusMinutes(15), 30);
        long staffAccountId = f.staffAccount(academyId, "직원");

        mockMvc.perform(get(ROSTER.formatted(runId)).header("Authorization", 토큰(staffAccountId, academyId,
                        Role.STAFF)))
                .andExpect(status().isForbidden());
    }

    // ── 호출 도우미 ─────────────────────────────────────────────────────

    private void 위치를_기록한다(long runId, OffsetDateTime receivedAt) {
        String key = "run:%d:position".formatted(runId);
        String json = """
                {"lat":37.400000,"lng":127.400000,"recordedAt":"%1$s","receivedAt":"%1$s","currentStopName":"중앙 집결지"}"""
                .formatted(receivedAt);
        stringRedisTemplate.opsForValue().set(key, json);
    }

    private String 토큰(long accountId, long academyId, Role role) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, role, AccountStatus.ACTIVE);
    }

    private String 메인관리자_토큰(long accountId) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, null, Role.SYSTEM_ADMIN, AccountStatus.ACTIVE);
    }
}
