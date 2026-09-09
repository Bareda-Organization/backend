package src.backend.location.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
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

import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.location.proximity.ProximityNotificationService;
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
import testsupport.redis.RedisTestContainerBase;

/**
 * T1 실제 쓰기 → T3·T4 실제 읽기를 잇는 시험(Phase 10 게이트 리뷰 R1 Critical 정정) — {@code POST
 * /runs/{runId}/position} 이 남긴 Redis 값을 손으로 만든 JSON 이 아니라 <b>진짜 소비자 코드</b>
 * ({@link ProximityNotificationService#judgeOne}·{@code GET /students/{id}/bus-position})로 읽어
 * 확인한다.
 *
 * <p>이 시험이 필요한 이유 — {@code RunPositionRedisIntegrationTest}·
 * {@code ProximityNotificationServiceTest}·{@code StudentBusPositionControllerTest} 는 전부 각자
 * 손으로 만든 평문 JSON 을 Redis 에 직접 심어 자기 쪽만 검사한다. T1 의 실제 쓰기 직렬화기가 바뀌어도
 * (예: 다시 {@code @class} 타입 태그를 심는 다형 직렬화기로) 그 세 시험 중 어느 것도 실패하지 않는다
 * — 이 시험만이 그 경계를 잇는다.
 *
 * <p>{@code @Transactional} 을 쓰지 않는 이유는 {@code RunPositionRedisIntegrationTest} 와 같다 —
 * {@link RunPositionRedisListener} 가 {@code AFTER_COMMIT} 으로 도는데 테스트 트랜잭션이 롤백하면
 * 커밋 자체가 일어나지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RunPositionCrossConsumerIntegrationTest extends RedisTestContainerBase {

    /** 정차지 좌표(37.500000, 127.000000) 기준 약 200m — {@code ProximityJudge} 문턱(300m) 안쪽. */
    private static final String NEAR_LAT = "37.501799";

    private static final String STOP_LNG = "127.000000";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    @Autowired
    private ProximityNotificationService proximityNotificationService;

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

    @TestConfiguration
    static class FixedClockConfig {

        private static final Instant FIXED = Instant.parse("2030-04-01T03:00:00Z"); // 2030-04-01 12:00 KST

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED, ZoneId.of("Asia/Seoul"));
        }
    }

    /** 이 클래스가 만든 학원 id — 뒷정리가 이 값으로만 지운다(다른 좌석·다른 시험의 행을 건드리지 않는다). */
    private final List<Long> academyIds = new ArrayList<>();

    private DriverRunFixtures fixtures() {
        return new DriverRunFixtures(academyRepository, busRepository, stopRepository, studentRepository,
                accountRepository, managerRepository, assignmentRepository, runRepository, confirmedRouteRepository,
                routeVersionRepository, runStopRepository, runRiderRepository, academyStaffRepository,
                guardianRepository, guardianStudentRepository, changeRequestRepository);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    /** FK 순서 — notification_log → run_position → run(계단식 assignment·confirmed_route·route_version·
     * run_stop·run_rider) → guardian(계단식 guardian_student) → student → manager → account → bus → stop → academy. */
    @AfterEach
    void 뒷정리한다() {
        if (academyIds.isEmpty()) {
            return;
        }
        Long[] ids = academyIds.toArray(new Long[0]);
        jdbcTemplate.update("DELETE FROM notification_log WHERE dedup_key LIKE ANY "
                + "(SELECT 'approaching:' || r.id || ':%' FROM run r WHERE r.academy_id = ANY(?))", (Object) ids);
        jdbcTemplate.update("DELETE FROM run_position WHERE run_id IN "
                + "(SELECT id FROM run WHERE academy_id = ANY(?))", (Object) ids);
        jdbcTemplate.update("DELETE FROM run WHERE academy_id = ANY(?)", (Object) ids);
        jdbcTemplate.update("DELETE FROM guardian WHERE academy_id = ANY(?)", (Object) ids);
        jdbcTemplate.update("DELETE FROM student WHERE academy_id = ANY(?)", (Object) ids);
        jdbcTemplate.update("DELETE FROM manager WHERE academy_id = ANY(?)", (Object) ids);
        jdbcTemplate.update("DELETE FROM account WHERE academy_id = ANY(?)", (Object) ids);
        jdbcTemplate.update("DELETE FROM bus WHERE academy_id = ANY(?)", (Object) ids);
        jdbcTemplate.update("DELETE FROM stop WHERE academy_id = ANY(?)", (Object) ids);
        jdbcTemplate.update("DELETE FROM academy WHERE id = ANY(?)", (Object) ids);
        academyIds.clear();
    }

    @Test
    @DisplayName("T1 실제 쓰기 → T3(근접 알림)·T4(학부모 위치 조회) 실제 읽기가 한 값으로 이어진다")
    void 실제_송신_값을_T3_T4_가_각자_읽어낸다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        academyIds.add(academyId);
        long busId = fixtures.bus(academyId);
        long stopId = fixtures.stop(academyId, "37.500000", STOP_LNG);
        long studentId = fixtures.student(academyId, "교차소비자시험학생");
        long guardianAccountId = fixtures.guardianOf(academyId, studentId, "교차소비자시험학부모", now());

        OffsetDateTime departTime = now();
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        fixtures.startRun(runId, now());
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        long versionId = fixtures.confirmedRouteWithVersion(runId, now());
        fixtures.runStopForStop(versionId, stopId, 1, now().plusMinutes(10));
        fixtures.rider(runId, studentId, stopId, RiderStatus.WAITING, null);

        OffsetDateTime recordedAt = now().minusSeconds(2);
        String body = """
                {"lat": %s, "lng": %s, "recorded_at": "%s"}
                """.formatted(NEAR_LAT, STOP_LNG, recordedAt);

        // 1. 실제 쓰기 경로 — 손으로 만든 JSON 을 Redis 에 심지 않고 실제 HTTP 호출로 채운다.
        mockMvc.perform(post("/api/v1/runs/" + runId + "/position")
                .header("Authorization", 기사_토큰(driverAccountId, academyId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isNoContent());

        // 2. T3 실제 읽기 — RunPositionReader 가 그 값을 성공적으로 파싱해야만 거리 판정까지 가서
        //    알림이 적재된다. 파싱이 실패하면(다형 태그 혼입 등) judgeOne 이 조용히 아무 것도 안 하고
        //    반환해 이 단언이 실패한다 — 그것이 이 시험이 검사하는 결함이다.
        proximityNotificationService.judgeOne(runId, academyId);

        String dedupKey = "approaching:%d:%d:%d".formatted(runId, stopId, studentId);
        Integer notificationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_log WHERE dedup_key = ?", Integer.class, dedupKey);
        assertThat(notificationCount).as("T3(RunPositionReader)가 T1 이 실제로 쓴 값을 파싱해 근접 알림을 적재해야 한다")
                .isEqualTo(1);

        // 3. T4 실제 읽기 — RunPositionCache 를 거쳐 GET 응답에 좌표가 그대로 나와야 한다.
        mockMvc.perform(get("/api/v1/students/" + studentId + "/bus-position")
                .header("Authorization", 학부모_토큰(guardianAccountId, academyId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.run_status").value("moving"))
                .andExpect(jsonPath("$.data.lat").value(Double.parseDouble(NEAR_LAT)))
                .andExpect(jsonPath("$.data.lng").value(Double.parseDouble(STOP_LNG)))
                .andExpect(jsonPath("$.data.received_at").exists());
    }

    private String 기사_토큰(long accountId, long academyId) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, Role.DRIVER, AccountStatus.ACTIVE);
    }

    private String 학부모_토큰(long accountId, long academyId) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, Role.PARENT, AccountStatus.ACTIVE);
    }
}
