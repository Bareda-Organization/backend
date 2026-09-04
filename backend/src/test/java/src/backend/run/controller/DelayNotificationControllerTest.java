package src.backend.run.controller;

import static org.assertj.core.api.Assertions.assertThat;
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

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

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
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.routing.entity.RunStop;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

/**
 * 지연 알림 신고 API(NTF-06, M-05, API_SPEC §4.9) — F3 S1 목표 1(엔드포인트 전체 행동) · 2(수신 범위
 * 3조건) · 3(중복·갱신 판정, Ruling 253).
 *
 * <p>픽스처는 {@code DriverRunFixtures} 를 그대로 재사용한다 — 회차·배치·라이더 생성 경로가 이
 * 시험이 필요로 하는 것과 완전히 같다(동승자 배치는 {@code assignedManager}·
 * {@code unassignedManager} 가 {@code ManagerRole} 을 인자로 받아 기사·동승자 어느 쪽도 만든다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DelayNotificationControllerTest {

    private static final String DELAY = "/api/v1/runs/%d/delay";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

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

    private DriverRunFixtures fixtures() {
        return new DriverRunFixtures(academyRepository, busRepository, stopRepository, studentRepository,
                accountRepository, managerRepository, assignmentRepository, runRepository, confirmedRouteRepository,
                routeVersionRepository, runStopRepository, runRiderRepository, academyStaffRepository,
                guardianRepository, guardianStudentRepository, changeRequestRepository);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    // ── goal 1 — 엔드포인트 전체 행동 ─────────────────────────────────────

    @Test
    @DisplayName("목표1 — 배치된 동승자의 정상 신고는 201 이고 세 수신 갈래 모두 발신되며 이력이 저장된다")
    void 정상_신고는_성공하고_이력이_저장된다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long stopId = fixtures.stop(academyId, "37.560000", "126.970000");
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, now(), now().minusMinutes(30));
        fixtures.startRun(runId, now());
        long escortAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.ESCORT, "동승자", now());
        fixtures.staffAccount(academyId, "관계자1");
        long studentId = fixtures.studentWithAccount(academyId, "학생1");
        fixtures.guardianOf(academyId, studentId, "학부모1", now());
        long versionId = fixtures.confirmedRouteWithVersion(runId, now());
        fixtures.runStopForStop(versionId, stopId, 1, now());
        fixtures.rider(runId, studentId, stopId, RiderStatus.WAITING, now());

        mockMvc.perform(post(DELAY.formatted(runId))
                        .header("Authorization", 토큰(escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(요청본문(10, "traffic", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.notified_staff").value(true))
                .andExpect(jsonPath("$.data.notified_guardians").value(true))
                .andExpect(jsonPath("$.data.notified_students").value(true));

        entityManager.flush();
        assertThat(지연알림_건수(runId)).isEqualTo(1);
        assertThat(지연알림_분(runId)).isEqualTo(10);
        assertThat(지연알림_사유(runId)).isEqualTo("traffic");
    }

    @Test
    @DisplayName("목표1 ESCORT_ONLY — 기사가 신고하면 403 ESCORT_ONLY 이고 이력이 남지 않는다")
    void 기사는_신고할_수_없다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, now(), now().minusMinutes(30));
        fixtures.startRun(runId, now());
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());

        mockMvc.perform(post(DELAY.formatted(runId))
                        .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(요청본문(10, "traffic", null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ESCORT_ONLY"));

        assertThat(지연알림_건수(runId)).isEqualTo(0);
    }

    @Test
    @DisplayName("목표1 FORBIDDEN — 같은 학원 동승자이지만 이 회차에 배치되지 않으면 403 FORBIDDEN(ESCORT_ONLY 아님)")
    void 배치되지_않은_같은_학원_동승자는_FORBIDDEN_이다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, now(), now().minusMinutes(30));
        fixtures.startRun(runId, now());
        fixtures.assignedManager(academyId, runId, ManagerRole.ESCORT, "배치된동승자", now());
        long unassignedEscortId = fixtures.unassignedManager(academyId, ManagerRole.ESCORT, "미배치동승자");

        mockMvc.perform(post(DELAY.formatted(runId))
                        .header("Authorization", 토큰(unassignedEscortId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(요청본문(10, "traffic", null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertThat(지연알림_건수(runId)).isEqualTo(0);
    }

    /**
     * {@link src.backend.run.access.RunAssignmentAccess#assertAssignedEscort} 가
     * {@link src.backend.run.command.DelayNotificationCommandService#notifyDelay} 안에서 회차 조회보다
     * 먼저 실행된다 — {@link src.backend.run.command.RunStartCommandService}·
     * {@link src.backend.run.command.RunArrivalCommandService} 와 같은 순서(그 클래스들도 배치 확인이
     * 먼저다). 배치 조회는 {@code runId} 존재를 전제하지 않으므로, 존재하지 않는 회차는 배치 행이 없어
     * RUN_NOT_FOUND 가 아니라 FORBIDDEN 으로 먼저 걸린다 — {@link DriverRunControllerTest} 에도
     * 존재하지 않는 회차를 RUN_NOT_FOUND 로 기대하는 시험이 없다(같은 이유).
     */
    @Test
    @DisplayName("목표1 FORBIDDEN — 존재하지 않는 회차는 배치 조회가 먼저 걸려 404 가 아니라 403 이다")
    void 존재하지_않는_회차는_FORBIDDEN_이다() throws Exception {
        mockMvc.perform(post(DELAY.formatted(999_999_999L))
                        .header("Authorization", 토큰(1L, 1L, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(요청본문(10, "traffic", null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("목표1 RUN_NOT_MOVING — 아직 시작하지 않은(confirmed) 회차는 409 다")
    void 시작하지_않은_회차는_RUN_NOT_MOVING_이다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, now(), now().minusMinutes(30));
        long escortAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.ESCORT, "동승자", now());

        mockMvc.perform(post(DELAY.formatted(runId))
                        .header("Authorization", 토큰(escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(요청본문(10, "traffic", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_MOVING"));
    }

    @Test
    @DisplayName("목표1 급소 — minutes 가 5의 배수가 아니면 422 VALIDATION_FAILED 다")
    void minutes_가_5의_배수가_아니면_422_다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, now(), now().minusMinutes(30));
        fixtures.startRun(runId, now());
        long escortAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.ESCORT, "동승자", now());

        mockMvc.perform(post(DELAY.formatted(runId))
                        .header("Authorization", 토큰(escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(요청본문(7, "traffic", null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertThat(지연알림_건수(runId)).isEqualTo(0);
    }

    @Test
    @DisplayName("목표1 급소 — 정의되지 않은 reason 문자열은 422 VALIDATION_FAILED 다")
    void 정의되지_않은_reason_은_422_다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, now(), now().minusMinutes(30));
        fixtures.startRun(runId, now());
        long escortAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.ESCORT, "동승자", now());

        mockMvc.perform(post(DELAY.formatted(runId))
                        .header("Authorization", 토큰(escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(요청본문(10, "typhoon", null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertThat(지연알림_건수(runId)).isEqualTo(0);
    }

    @Test
    @DisplayName("목표1 — minutes 가 아예 없으면(빈 검증 단계) 422 다 — GlobalExceptionHandler 가 "
            + "MethodArgumentNotValidException 도 VALIDATION_FAILED 로 합류시킨다")
    void minutes_가_없으면_422_다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, now(), now().minusMinutes(30));
        fixtures.startRun(runId, now());
        long escortAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.ESCORT, "동승자", now());

        mockMvc.perform(post(DELAY.formatted(runId))
                        .header("Authorization", 토큰(escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"traffic\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("목표1 — reason 이 빈 문자열이면(빈 검증 단계) 422 다")
    void reason_이_빈_문자열이면_422_다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, now(), now().minusMinutes(30));
        fixtures.startRun(runId, now());
        long escortAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.ESCORT, "동승자", now());

        mockMvc.perform(post(DELAY.formatted(runId))
                        .header("Authorization", 토큰(escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(요청본문(10, "", null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    // ── goal 2 — 수신 범위 3조건(각각 독립으로 실패해야 한다) ───────────────

    @Test
    @DisplayName("목표2-a — 이미 지난(도착 완료) 정류장의 학생은 수신 대상에서 빠진다")
    void 이미_지난_정류장의_학생은_제외된다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long stopPast = fixtures.stop(academyId, "37.560000", "126.970000");
        long stopFuture = fixtures.stop(academyId, "37.570000", "126.980000");
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, now(), now().minusMinutes(30));
        fixtures.startRun(runId, now());
        long escortAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.ESCORT, "동승자", now());
        long versionId = fixtures.confirmedRouteWithVersion(runId, now());
        long pastRunStopId = fixtures.runStopForStop(versionId, stopPast, 1, now());
        fixtures.runStopForStop(versionId, stopFuture, 2, now());
        markArrived(pastRunStopId, now());

        long pastStudentId = fixtures.studentWithAccount(academyId, "지난정류장학생");
        fixtures.guardianOf(academyId, pastStudentId, "지난정류장학부모", now());
        fixtures.rider(runId, pastStudentId, stopPast, RiderStatus.WAITING, now());

        long futureStudentId = fixtures.studentWithAccount(academyId, "다음정류장학생");
        fixtures.guardianOf(academyId, futureStudentId, "다음정류장학부모", now());
        fixtures.rider(runId, futureStudentId, stopFuture, RiderStatus.WAITING, now());

        mockMvc.perform(post(DELAY.formatted(runId))
                        .header("Authorization", 토큰(escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(요청본문(10, "traffic", null)))
                .andExpect(status().isCreated());

        entityManager.flush();
        assertThat(학부모_알림수(runId, pastStudentId)).as("이미 지난 정류장 학생의 보호자는 받지 않아야 한다").isEqualTo(0);
        assertThat(학부모_알림수(runId, futureStudentId)).as("아직 지나지 않은 정류장 학생의 보호자는 받아야 한다").isEqualTo(1);
    }

    @Test
    @DisplayName("목표2-b — 이미 탑승한(BOARDED) 학생은 수신 대상에서 빠진다")
    void 이미_탑승한_학생은_제외된다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long stopId = fixtures.stop(academyId, "37.560000", "126.970000");
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, now(), now().minusMinutes(30));
        fixtures.startRun(runId, now());
        long escortAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.ESCORT, "동승자", now());
        long versionId = fixtures.confirmedRouteWithVersion(runId, now());
        fixtures.runStopForStop(versionId, stopId, 1, now());

        long boardedStudentId = fixtures.studentWithAccount(academyId, "탑승학생");
        fixtures.guardianOf(academyId, boardedStudentId, "탑승학부모", now());
        fixtures.rider(runId, boardedStudentId, stopId, RiderStatus.BOARDED, now());

        long waitingStudentId = fixtures.studentWithAccount(academyId, "대기학생");
        fixtures.guardianOf(academyId, waitingStudentId, "대기학부모", now());
        fixtures.rider(runId, waitingStudentId, stopId, RiderStatus.WAITING, now());

        mockMvc.perform(post(DELAY.formatted(runId))
                        .header("Authorization", 토큰(escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(요청본문(10, "traffic", null)))
                .andExpect(status().isCreated());

        entityManager.flush();
        assertThat(학부모_알림수(runId, boardedStudentId)).as("이미 탑승한 학생의 보호자는 받지 않아야 한다").isEqualTo(0);
        assertThat(학부모_알림수(runId, waitingStudentId)).as("아직 대기 중인 학생의 보호자는 받아야 한다").isEqualTo(1);
    }

    @Test
    @DisplayName("목표2-c — 결석 처리된(ABSENT) 학생은 수신 대상에서 빠진다")
    void 결석_처리된_학생은_제외된다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long stopId = fixtures.stop(academyId, "37.560000", "126.970000");
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, now(), now().minusMinutes(30));
        fixtures.startRun(runId, now());
        long escortAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.ESCORT, "동승자", now());
        long versionId = fixtures.confirmedRouteWithVersion(runId, now());
        fixtures.runStopForStop(versionId, stopId, 1, now());

        long absentStudentId = fixtures.studentWithAccount(academyId, "결석학생");
        fixtures.guardianOf(academyId, absentStudentId, "결석학부모", now());
        fixtures.rider(runId, absentStudentId, stopId, RiderStatus.ABSENT, now());

        long waitingStudentId = fixtures.studentWithAccount(academyId, "대기학생2");
        fixtures.guardianOf(academyId, waitingStudentId, "대기학부모2", now());
        fixtures.rider(runId, waitingStudentId, stopId, RiderStatus.WAITING, now());

        mockMvc.perform(post(DELAY.formatted(runId))
                        .header("Authorization", 토큰(escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(요청본문(10, "traffic", null)))
                .andExpect(status().isCreated());

        entityManager.flush();
        assertThat(학부모_알림수(runId, absentStudentId)).as("결석 처리된 학생의 보호자는 받지 않아야 한다").isEqualTo(0);
        assertThat(학부모_알림수(runId, waitingStudentId)).as("대기 중인 학생의 보호자는 받아야 한다").isEqualTo(1);
    }

    // ── goal 3 — 중복·갱신 판정(Ruling 253) ─────────────────────────────

    @Test
    @DisplayName("목표3-a — 직전 신고와 minutes·reason·message 가 전부 같으면 409 DELAY_DUPLICATE 다")
    void 완전히_같은_내용의_재신고는_409_다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, now(), now().minusMinutes(30));
        fixtures.startRun(runId, now());
        long escortAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.ESCORT, "동승자", now());
        fixtures.confirmedRouteWithVersion(runId, now());

        mockMvc.perform(post(DELAY.formatted(runId))
                        .header("Authorization", 토큰(escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(요청본문(10, "traffic", null)))
                .andExpect(status().isCreated());

        mockMvc.perform(post(DELAY.formatted(runId))
                        .header("Authorization", 토큰(escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(요청본문(10, "traffic", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DELAY_DUPLICATE"));

        entityManager.flush();
        assertThat(지연알림_건수(runId)).isEqualTo(1);
    }

    @Test
    @DisplayName("목표3-b — minutes 만 달라도 새 신고로 처리된다")
    void minutes_만_다르면_새_신고다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, now(), now().minusMinutes(30));
        fixtures.startRun(runId, now());
        long escortAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.ESCORT, "동승자", now());
        fixtures.confirmedRouteWithVersion(runId, now());

        mockMvc.perform(post(DELAY.formatted(runId))
                        .header("Authorization", 토큰(escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(요청본문(10, "traffic", null)))
                .andExpect(status().isCreated());

        mockMvc.perform(post(DELAY.formatted(runId))
                        .header("Authorization", 토큰(escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(요청본문(15, "traffic", null)))
                .andExpect(status().isCreated());

        entityManager.flush();
        assertThat(지연알림_건수(runId)).isEqualTo(2);
    }

    @Test
    @DisplayName("목표3-c — reason 만 달라도 새 신고로 처리된다")
    void reason_만_다르면_새_신고다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, now(), now().minusMinutes(30));
        fixtures.startRun(runId, now());
        long escortAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.ESCORT, "동승자", now());
        fixtures.confirmedRouteWithVersion(runId, now());

        mockMvc.perform(post(DELAY.formatted(runId))
                        .header("Authorization", 토큰(escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(요청본문(10, "traffic", null)))
                .andExpect(status().isCreated());

        mockMvc.perform(post(DELAY.formatted(runId))
                        .header("Authorization", 토큰(escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(요청본문(10, "weather", null)))
                .andExpect(status().isCreated());

        entityManager.flush();
        assertThat(지연알림_건수(runId)).isEqualTo(2);
    }

    @Test
    @DisplayName("목표3-d — message 만 달라도(둘 다 채워짐) 새 신고로 처리된다")
    void message_만_다르면_새_신고다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, now(), now().minusMinutes(30));
        fixtures.startRun(runId, now());
        long escortAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.ESCORT, "동승자", now());
        fixtures.confirmedRouteWithVersion(runId, now());

        mockMvc.perform(post(DELAY.formatted(runId))
                        .header("Authorization", 토큰(escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(요청본문(10, "traffic", "곧 도착합니다")))
                .andExpect(status().isCreated());

        mockMvc.perform(post(DELAY.formatted(runId))
                        .header("Authorization", 토큰(escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(요청본문(10, "traffic", "조금 더 걸립니다")))
                .andExpect(status().isCreated());

        entityManager.flush();
        assertThat(지연알림_건수(runId)).isEqualTo(2);
    }

    @Test
    @DisplayName("목표3-e — message 가 둘 다 같은 값으로 채워져 있어도 나머지가 같으면 409 다(동치 비교가 null 전용이 아님)")
    void message_가_같은_값이어도_나머지가_같으면_중복이다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, now(), now().minusMinutes(30));
        fixtures.startRun(runId, now());
        long escortAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.ESCORT, "동승자", now());
        fixtures.confirmedRouteWithVersion(runId, now());

        mockMvc.perform(post(DELAY.formatted(runId))
                        .header("Authorization", 토큰(escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(요청본문(10, "traffic", "같은문구")))
                .andExpect(status().isCreated());

        mockMvc.perform(post(DELAY.formatted(runId))
                        .header("Authorization", 토큰(escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(요청본문(10, "traffic", "같은문구")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DELAY_DUPLICATE"));

        entityManager.flush();
        assertThat(지연알림_건수(runId)).isEqualTo(1);
    }

    @Test
    @DisplayName("목표3-f — message 가 null 에서 채워진 값으로 바뀌면(그 반대도) 다른 신고로 처리된다")
    void message_유무가_다르면_새_신고다() throws Exception {
        DriverRunFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, now(), now().minusMinutes(30));
        fixtures.startRun(runId, now());
        long escortAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.ESCORT, "동승자", now());
        fixtures.confirmedRouteWithVersion(runId, now());

        mockMvc.perform(post(DELAY.formatted(runId))
                        .header("Authorization", 토큰(escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(요청본문(10, "traffic", null)))
                .andExpect(status().isCreated());

        mockMvc.perform(post(DELAY.formatted(runId))
                        .header("Authorization", 토큰(escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(요청본문(10, "traffic", "문구가 생겼다")))
                .andExpect(status().isCreated());

        entityManager.flush();
        assertThat(지연알림_건수(runId)).isEqualTo(2);
    }

    // ── 픽스처 · 호출 도우미 ──────────────────────────────────────────────

    private String 토큰(long accountId, long academyId, Role role) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, role, AccountStatus.ACTIVE);
    }

    private String 요청본문(int minutes, String reason, String message) {
        String messageJson = message == null ? "null" : "\"" + message + "\"";
        return "{\"minutes\":%d,\"reason\":\"%s\",\"message\":%s}".formatted(minutes, reason, messageJson);
    }

    private void markArrived(long runStopId, OffsetDateTime arrivedAt) {
        RunStop stop = runStopRepository.findById(runStopId).orElseThrow();
        stop.markArrived(arrivedAt);
        runStopRepository.save(stop);
        entityManager.flush();
    }

    private long 지연알림_건수(long runId) {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM delay_notice WHERE run_id = ?",
                Integer.class, runId);
        return count == null ? 0 : count;
    }

    private int 지연알림_분(long runId) {
        Integer minutes = jdbcTemplate.queryForObject(
                "SELECT minutes FROM delay_notice WHERE run_id = ? ORDER BY sent_at DESC LIMIT 1", Integer.class,
                runId);
        return minutes == null ? -1 : minutes;
    }

    private String 지연알림_사유(long runId) {
        return jdbcTemplate.queryForObject(
                "SELECT reason FROM delay_notice WHERE run_id = ? ORDER BY sent_at DESC LIMIT 1", String.class,
                runId);
    }

    /** {@code dedup_key} 의 대상 자리가 studentId 인 학부모(PARENT) 알림 건수(커맨드 서비스 자바독 참고). */
    private long 학부모_알림수(long runId, long studentId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_log WHERE type = 'delay' AND recipient_role = 'parent' "
                        + "AND dedup_key LIKE ?",
                Integer.class, "delay:" + runId + ":" + studentId + ":%");
        return count == null ? 0 : count;
    }
}
