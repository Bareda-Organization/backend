package src.backend.boarding.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.annotation.Import;

import src.backend.academy.entity.AcademySetting;
import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademySettingRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.command.BoardingCommandFixtures;
import src.backend.boarding.event.RiderStatusChangedEvent;
import src.backend.run.event.RunEndedEvent;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.command.StopDepartureService;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import testsupport.clock.FixedClock20300401Config;

/**
 * 승하차 처리(§4.6)·되돌리기(§4.7) — Phase 9(T3) 목표 4·7·12·13·14. 목표 11(하원 자동 승차)은
 * {@link src.backend.boarding.command.AutoBoardingServiceTest} 가 별도로 다룬다 — 이 워크트리에는
 * T2 소유의 회차 시작 엔드포인트가 없어 {@link src.backend.boarding.command.AutoBoardingService} 를
 * 직접 호출해야 하고, 그 호출은 컨트롤러 계층을 거치지 않기 때문이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
@Transactional
@Import(FixedClock20300401Config.class)
class BoardingControllerTest {

    private static final String UPDATE_STATUS = "/api/v1/runs/%d/riders/%d";

    private static final String REVERT = "/api/v1/runs/%d/riders/%d/revert";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationEvents applicationEvents;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private AcademySettingRepository academySettingRepository;

    @Autowired
    private BusRepository busRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private GuardianRepository guardianRepository;

    @Autowired
    private GuardianStudentRepository guardianStudentRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AcademyStaffRepository academyStaffRepository;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private StopRepository stopRepository;

    @Autowired
    private RunRiderRepository runRiderRepository;

    @Autowired
    private ConfirmedRouteRepository confirmedRouteRepository;

    @Autowired
    private RouteVersionRepository routeVersionRepository;

    @Autowired
    private RunStopRepository runStopRepository;

    @Autowired
    private ManagerRepository managerRepository;

    @Autowired
    private AssignmentRepository assignmentRepository;

    @Autowired
    private StopDepartureService stopDepartureService;

    private BoardingCommandFixtures fixtures;


    private BoardingCommandFixtures fixtures() {
        if (fixtures == null) {
            fixtures = new BoardingCommandFixtures(academyRepository, busRepository, studentRepository,
                    guardianRepository, guardianStudentRepository, accountRepository, academyStaffRepository,
                    runRepository, stopRepository, runRiderRepository, confirmedRouteRepository,
                    routeVersionRepository, runStopRepository, jdbcTemplate, entityManager);
        }
        return fixtures;
    }

    // ── 목표 4 — 동승자 전용, 그 외 역할은 403 ESCORT_ONLY ──────────────────

    @Test
    @DisplayName("목표4 — 동승자 토큰으로 승하차 상태를 변경하면 성공한다")
    void 동승자_토큰으로_승하차_상태를_변경하면_성공한다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long academyId = fixtures().academy();
        long busId = fixtures().bus(academyId);
        long stopId = fixtures().stop(academyId, "37.500000", "127.000000");
        long studentId = fixtures().student(academyId, "학생1");
        long runId = fixtures().movingRun(academyId, busId, now.minusMinutes(10), now.minusMinutes(40));
        long riderId = fixtures().runRider(runId, studentId, stopId);
        long escortAccountId = fixtures().assignedManager(managerRepository, assignmentRepository, academyId, runId,
                ManagerRole.ESCORT, now);

        mockMvc.perform(patch(UPDATE_STATUS.formatted(runId, riderId))
                        .header("Authorization", 토큰(escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusUpdateBody("boarded", "manual", UUID.randomUUID(), now)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rider_id").value(riderId))
                .andExpect(jsonPath("$.data.status").value("boarded"));

        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM run_rider WHERE id = ?", String.class, riderId))
                .isEqualTo("boarded");
    }

    @Test
    @DisplayName("목표4 — 기사 토큰으로 승하차 상태를 바꾸려 하면 403 ESCORT_ONLY 이고 저장값은 그대로다")
    void 기사_토큰으로_바꾸려_하면_403이고_저장값은_불변이다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long academyId = fixtures().academy();
        long busId = fixtures().bus(academyId);
        long stopId = fixtures().stop(academyId, "37.500000", "127.000000");
        long studentId = fixtures().student(academyId, "학생2");
        long runId = fixtures().movingRun(academyId, busId, now.minusMinutes(10), now.minusMinutes(40));
        long riderId = fixtures().runRider(runId, studentId, stopId);
        long driverAccountId = fixtures().driverAccount(academyId);

        mockMvc.perform(patch(UPDATE_STATUS.formatted(runId, riderId))
                        .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusUpdateBody("boarded", "manual", UUID.randomUUID(), now)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ESCORT_ONLY"));

        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM run_rider WHERE id = ?", String.class, riderId))
                .as("거부됐으니 최초 상태(waiting) 그대로여야 한다")
                .isEqualTo("waiting");
    }

    // ── 목표 7 — no_show 처리 시 케이스 생성 + 학부모·관계자 알림 ────────────

    /**
     * R15-T3(Ruling 308·311) — 미승차는 관계자 갈래만 즉시고, 학부모 갈래는 그 승하차지를 출발할
     * 때로 옮겨 갔다. {@code no_show_처리하면_케이스와_알림_두_행이_생긴다}(Phase 9 원본)를 이
     * 시점 분리에 맞게 다시 썼다.
     */
    @Test
    @DisplayName("목표7(Ruling 311) — no_show 처리 즉시는 관계자 알림만 적재되고, 학부모 알림은 출발 시점에 적재된다")
    void no_show_처리하면_관계자_알림은_즉시_학부모_알림은_출발_시점에_적재된다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long academyId = fixtures().academy();
        long busId = fixtures().bus(academyId);
        long stopId = fixtures().stop(academyId, "37.500000", "127.000000");
        long studentId = fixtures().student(academyId, "학생3");
        BoardingCommandFixtures.GuardianAccount guardian = fixtures().guardian(academyId, "보호자3");
        fixtures().linkChild(guardian.guardianId(), studentId, now.minusDays(1));
        long staffAccountId = fixtures().staffAccount(academyId);
        long runId = fixtures().movingRun(academyId, busId, now.minusMinutes(10), now.minusMinutes(40));
        fixtures().confirmedRunStop(runId, stopId, now.minusMinutes(30));
        long riderId = fixtures().runRider(runId, studentId, stopId);
        long escortAccountId = fixtures().assignedManager(managerRepository, assignmentRepository, academyId, runId,
                ManagerRole.ESCORT, now);

        mockMvc.perform(patch(UPDATE_STATUS.formatted(runId, riderId))
                        .header("Authorization", 토큰(escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusUpdateBody("no_show", "manual", UUID.randomUUID(), now)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("no_show"))
                .andExpect(jsonPath("$.data.no_show_case.case_id").isString())
                .andExpect(jsonPath("$.data.no_show_case.contacts").isEmpty());

        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM run_rider WHERE id = ?", String.class, riderId))
                .as("①run_rider.status")
                .isEqualTo("no_show");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM no_show_case WHERE run_rider_id = ?",
                Integer.class, riderId)).as("②케이스 행").isEqualTo(1);

        List<String> immediateRoles = jdbcTemplate.queryForList(
                "SELECT recipient_role FROM notification_log WHERE recipient_account_id IN (?, ?)", String.class,
                guardian.accountId(), staffAccountId);
        assertThat(immediateRoles).as("③즉시는 관계자(staff) 1행뿐 — 학부모(parent)는 아직 없다")
                .containsExactly("staff");

        출발_처리(runId, academyId, stopId, now.plusMinutes(1));
        entityManager.flush();

        List<java.util.Map<String, Object>> parentRows = jdbcTemplate.queryForList(
                "SELECT type, student_id, student_name FROM notification_log "
                        + "WHERE recipient_account_id = ? AND recipient_role = 'parent'",
                guardian.accountId());
        assertThat(parentRows).as("④출발 후 학부모 알림 1행").hasSize(1);
        assertThat(parentRows.get(0).get("type")).isEqualTo("no_show");
        assertThat(parentRows.get(0).get("student_id")).as("⑤student_id 가 채워진다").isEqualTo(studentId);
        assertThat(parentRows.get(0).get("student_name")).as("⑥student_name 이 채워진다").isEqualTo("학생3");
    }

    // ── Phase 11 목표 2 — 학원별 미승차 대기 시간(EXC-01, API_SPEC §5.21)이 실제로 적용된다 ──

    /**
     * 두 학원에 서로 다른 {@code no_show_wait_minutes} 를 설정하고 각각 no_show 처리하면, 케이스의
     * 만료 시각이 자기 학원 값만 반영해야 한다 — 한 값(예: 기본 3분)만 검사하면 상수를 그대로 쓰는
     * 결함도 우연히 통과하므로, 서로 다른 두 값을 함께 확인해 "학원별로 실제로 갈린다"를 검증한다.
     */
    @Test
    @DisplayName("Phase11 목표2 — 학원마다 다른 대기 시간을 설정하면 no_show 만료 시각에 각자 값이 그대로 반영된다")
    void 학원별_미승차_대기_시간이_다르면_만료_시각도_각자_다르게_반영된다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);

        long academyA = fixtures().academy();
        AcademySetting settingA = academySettingRepository.save(AcademySetting.forAcademy(academyA));
        settingA.changeNoShowWaitMinutes(7);
        // fixtures().movingRun() 이 내부에서 entityManager.clear() 를 호출한다(BoardingCommandFixtures
        // 자바독 참고) — 그 전에 flush 해 두지 않으면 위 mutation 이 영속성 컨텍스트에서 통째로
        // 사라져 academy_setting 행 자체가 커밋되지 않는다(실측: 없이 돌리면 3분(기본값)으로 나온다).
        entityManager.flush();
        long busA = fixtures().bus(academyA);
        long stopA = fixtures().stop(academyA, "37.530000", "127.030000");
        long studentA = fixtures().student(academyA, "학생9-A");
        long runA = fixtures().movingRun(academyA, busA, now.minusMinutes(10), now.minusMinutes(40));
        long riderA = fixtures().runRider(runA, studentA, stopA);
        long escortA = fixtures().assignedManager(managerRepository, assignmentRepository, academyA, runA,
                ManagerRole.ESCORT, now);

        long academyB = fixtures().academy();
        AcademySetting settingB = academySettingRepository.save(AcademySetting.forAcademy(academyB));
        settingB.changeNoShowWaitMinutes(12);
        entityManager.flush();
        long busB = fixtures().bus(academyB);
        long stopB = fixtures().stop(academyB, "37.540000", "127.040000");
        long studentB = fixtures().student(academyB, "학생9-B");
        long runB = fixtures().movingRun(academyB, busB, now.minusMinutes(10), now.minusMinutes(40));
        long riderB = fixtures().runRider(runB, studentB, stopB);
        long escortB = fixtures().assignedManager(managerRepository, assignmentRepository, academyB, runB,
                ManagerRole.ESCORT, now);

        entityManager.flush();

        mockMvc.perform(patch(UPDATE_STATUS.formatted(runA, riderA))
                        .header("Authorization", 토큰(escortA, academyA, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusUpdateBody("no_show", "manual", UUID.randomUUID(), now)))
                .andExpect(status().isOk());
        mockMvc.perform(patch(UPDATE_STATUS.formatted(runB, riderB))
                        .header("Authorization", 토큰(escortB, academyB, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusUpdateBody("no_show", "manual", UUID.randomUUID(), now)))
                .andExpect(status().isOk());

        entityManager.flush();
        assertThat(대기_분(riderA)).as("①A학원 7분 설정이 그대로 반영").isEqualTo(7);
        assertThat(대기_분(riderB)).as("②B학원 12분 설정이 그대로 반영 — A 값과 섞이지 않는다").isEqualTo(12);
    }

    /** 학원 설정 행이 아예 없으면 {@link AcademySetting#DEFAULT_NO_SHOW_WAIT_MINUTES}(3분)로 떨어진다. */
    @Test
    @DisplayName("Phase11 목표2 — 학원 설정 행이 없으면 기본 대기 시간(3분)이 적용된다")
    void 학원_설정이_없으면_기본_3분이_적용된다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long academyId = fixtures().academy();
        assertThat(academySettingRepository.findById(academyId)).as("설정 행을 만들지 않았다").isEmpty();
        long busId = fixtures().bus(academyId);
        long stopId = fixtures().stop(academyId, "37.550000", "127.050000");
        long studentId = fixtures().student(academyId, "학생9-기본값");
        long runId = fixtures().movingRun(academyId, busId, now.minusMinutes(10), now.minusMinutes(40));
        long riderId = fixtures().runRider(runId, studentId, stopId);
        long escortAccountId = fixtures().assignedManager(managerRepository, assignmentRepository, academyId, runId,
                ManagerRole.ESCORT, now);

        mockMvc.perform(patch(UPDATE_STATUS.formatted(runId, riderId))
                        .header("Authorization", 토큰(escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusUpdateBody("no_show", "manual", UUID.randomUUID(), now)))
                .andExpect(status().isOk());

        entityManager.flush();
        assertThat(대기_분(riderId)).isEqualTo(AcademySetting.DEFAULT_NO_SHOW_WAIT_MINUTES);
    }

    /** {@code no_show_case.started_at} ~ {@code expires_at} 간격(분) — 목표2 검증에 공용으로 쓴다. */
    private long 대기_분(long runRiderId) {
        OffsetDateTime startedAt = jdbcTemplate.queryForObject(
                "SELECT started_at FROM no_show_case WHERE run_rider_id = ?", OffsetDateTime.class, runRiderId);
        OffsetDateTime expiresAt = jdbcTemplate.queryForObject(
                "SELECT expires_at FROM no_show_case WHERE run_rider_id = ?", OffsetDateTime.class, runRiderId);
        return Duration.between(startedAt, expiresAt).toMinutes();
    }

    // ── 목표 7 — 운행 중 미승차로 잔여 0명이 된 정차지는 stop_skipped (C-05 후자 경로) ──────

    /**
     * 그 정차지에 이미 부재 처리된 탑승자(함정 — {@code absent} 를 잔여로 잘못 세면 이 시험이 통과해도
     * 아무것도 검증하지 않는다) + 지금 미승차 처리하는 탑승자 1명뿐이면, 미승차 처리 직후 잔여가
     * 0명이 되어 {@code stop_skipped=true} 이고 {@code run_stop.change='skipped'} 로 실제 전환된다.
     */
    @Test
    @DisplayName("목표7 — 정차지 잔여가 0명이 되는 미승차는 stop_skipped=true 이고 run_stop 이 실제로 skipped 전환된다")
    void 미승차로_정차지_잔여가_0명이면_stop_skipped_true_이고_run_stop_이_skipped_로_전환된다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long academyId = fixtures().academy();
        long busId = fixtures().bus(academyId);
        long stopId = fixtures().stop(academyId, "37.510000", "127.010000");
        long runId = fixtures().movingRun(academyId, busId, now.minusMinutes(10), now.minusMinutes(40));
        long runStopId = fixtures().confirmedRunStop(runId, stopId, now.minusHours(1));

        long alreadyAbsentStudentId = fixtures().student(academyId, "학생7-이미부재");
        long alreadyAbsentRiderId = fixtures().runRider(runId, alreadyAbsentStudentId, stopId);
        fixtures().markAbsent(alreadyAbsentRiderId);

        long studentId = fixtures().student(academyId, "학생7-미승차");
        long riderId = fixtures().runRider(runId, studentId, stopId);
        long escortAccountId = fixtures().assignedManager(managerRepository, assignmentRepository, academyId, runId,
                ManagerRole.ESCORT, now);

        mockMvc.perform(patch(UPDATE_STATUS.formatted(runId, riderId))
                        .header("Authorization", 토큰(escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusUpdateBody("no_show", "manual", UUID.randomUUID(), now)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("no_show"))
                .andExpect(jsonPath("$.data.stop_skipped").value(true));

        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT change FROM run_stop WHERE id = ?", String.class, runStopId))
                .as("잔여 0명이니 run_stop 이 실제로 skipped 로 전환돼야 한다").isEqualTo("skipped");
    }

    /**
     * 같은 정차지에 아직 남은(waiting) 탑승자가 있으면 미승차 처리해도 {@code stop_skipped=false} 이고
     * {@code run_stop} 은 전환되지 않는다.
     */
    @Test
    @DisplayName("목표7 — 정차지에 잔여 탑승자가 남으면 stop_skipped=false 이고 run_stop 은 전환되지 않는다")
    void 정차지에_잔여_탑승자가_남으면_stop_skipped_false_이다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long academyId = fixtures().academy();
        long busId = fixtures().bus(academyId);
        long stopId = fixtures().stop(academyId, "37.520000", "127.020000");
        long runId = fixtures().movingRun(academyId, busId, now.minusMinutes(10), now.minusMinutes(40));
        long runStopId = fixtures().confirmedRunStop(runId, stopId, now.minusHours(1));

        long remainingStudentId = fixtures().student(academyId, "학생8-잔여");
        fixtures().runRider(runId, remainingStudentId, stopId);

        long studentId = fixtures().student(academyId, "학생8-미승차");
        long riderId = fixtures().runRider(runId, studentId, stopId);
        long escortAccountId = fixtures().assignedManager(managerRepository, assignmentRepository, academyId, runId,
                ManagerRole.ESCORT, now);

        mockMvc.perform(patch(UPDATE_STATUS.formatted(runId, riderId))
                        .header("Authorization", 토큰(escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusUpdateBody("no_show", "manual", UUID.randomUUID(), now)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("no_show"))
                .andExpect(jsonPath("$.data.stop_skipped").value(false));

        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT change FROM run_stop WHERE id = ?", String.class, runStopId))
                .as("잔여가 남아 있으니 run_stop 은 전환되면 안 된다").isNull();
    }

    // ── 목표 12 — client_key 재전송 멱등 ──────────────────────────────────

    @Test
    @DisplayName("목표12 — 같은 client_key 로 재전송하면 상태가 두 번 바뀌지 않고 동일한 200 응답이다")
    void 같은_client_key_재전송은_상태를_두번_바꾸지_않는다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long academyId = fixtures().academy();
        long busId = fixtures().bus(academyId);
        long stopId = fixtures().stop(academyId, "37.500000", "127.000000");
        long studentId = fixtures().student(academyId, "학생4");
        BoardingCommandFixtures.GuardianAccount guardian = fixtures().guardian(academyId, "보호자4");
        fixtures().linkChild(guardian.guardianId(), studentId, now.minusDays(1));
        long runId = fixtures().movingRun(academyId, busId, now.minusMinutes(10), now.minusMinutes(40));
        long riderId = fixtures().runRider(runId, studentId, stopId);
        long escortAccountId = fixtures().assignedManager(managerRepository, assignmentRepository, academyId, runId,
                ManagerRole.ESCORT, now);
        UUID clientKey = UUID.randomUUID();
        String body = statusUpdateBody("boarded", "manual", clientKey, now);

        String firstResponse = mockMvc
                .perform(patch(UPDATE_STATUS.formatted(runId, riderId))
                        .header("Authorization", 토큰(escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        entityManager.flush();

        String secondResponse = mockMvc
                .perform(patch(UPDATE_STATUS.formatted(runId, riderId))
                        .header("Authorization", 토큰(escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(secondResponse).as("②응답 본문 동일성").isEqualTo(firstResponse);

        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM rider_status_history WHERE run_rider_id = ?",
                Integer.class, riderId)).as("③이력 행 수").isEqualTo(1);
        // R15-T3(Ruling 308) — 승차는 그 승하차지를 출발할 때 통지된다. 이 시험은 출발까지 가지
        // 않으므로 재전송을 두 번 반복해도 알림은 여전히 0건이어야 한다.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_log WHERE recipient_account_id = ?", Integer.class,
                guardian.accountId())).as("④알림 건수 — 출발 전이라 0건").isEqualTo(0);
    }

    // ── 목표 13 — 되돌리기가 직전 상태로 실제로 되돌린다 ─────────────────────

    @Test
    @DisplayName("목표13 — 되돌리기는 직전 상태로 실제로 되돌리고 이력을 남긴다")
    void 되돌리기는_직전_상태로_되돌리고_이력을_남긴다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long academyId = fixtures().academy();
        long busId = fixtures().bus(academyId);
        long stopId = fixtures().stop(academyId, "37.500000", "127.000000");
        long studentId = fixtures().student(academyId, "학생5");
        long runId = fixtures().movingRun(academyId, busId, now.minusMinutes(10), now.minusMinutes(40));
        long riderId = fixtures().runRider(runId, studentId, stopId);
        long escortAccountId = fixtures().assignedManager(managerRepository, assignmentRepository, academyId, runId,
                ManagerRole.ESCORT, now);

        mockMvc.perform(patch(UPDATE_STATUS.formatted(runId, riderId))
                        .header("Authorization", 토큰(escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusUpdateBody("boarded", "manual", UUID.randomUUID(), now)))
                .andExpect(status().isOk());
        entityManager.flush();

        mockMvc.perform(post(REVERT.formatted(runId, riderId))
                        .header("Authorization", 토큰(escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("waiting"));

        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM run_rider WHERE id = ?", String.class, riderId))
                .as("②재조회 저장값").isEqualTo("waiting");

        List<java.util.Map<String, Object>> history = jdbcTemplate.queryForList(
                "SELECT from_status, to_status, is_revert FROM rider_status_history "
                        + "WHERE run_rider_id = ? ORDER BY id", riderId);
        assertThat(history).as("③이력 행 — 최초 처리 1건 + 되돌리기 1건").hasSize(2);
        assertThat(history.get(1).get("from_status")).isEqualTo("boarded");
        assertThat(history.get(1).get("to_status")).isEqualTo("waiting");
        assertThat(history.get(1).get("is_revert")).isEqualTo(true);

        // ④ rider_changed 방송의 재료 — revert 도 §7.1 트리거 표에 명시돼 있는데(리뷰 R2 판정문
        // §②변형8), 지금까지는 DB 결과만 보고 이 발행을 검사하지 않았다.
        assertThat(applicationEvents.stream(RiderStatusChangedEvent.class))
                .as("④되돌리기도 RiderStatusChangedEvent 를 발행한다")
                .anySatisfy(event -> {
                    assertThat(event.runRiderId()).isEqualTo(riderId);
                    assertThat(event.status()).isEqualTo("waiting");
                });
    }

    // ── 목표 14 — 되돌리기 횟수·시간 제한 부재 ──────────────────────────────

    @Test
    @DisplayName("목표14 — 되돌리기를 반복해도 횟수 제한 없이 매번 200 이고 이력이 계속 쌓인다")
    void 되돌리기를_반복해도_제한이_없다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long academyId = fixtures().academy();
        long busId = fixtures().bus(academyId);
        long stopId = fixtures().stop(academyId, "37.500000", "127.000000");
        long studentId = fixtures().student(academyId, "학생6");
        long runId = fixtures().movingRun(academyId, busId, now.minusMinutes(10), now.minusMinutes(40));
        long riderId = fixtures().runRider(runId, studentId, stopId);
        long escortAccountId = fixtures().assignedManager(managerRepository, assignmentRepository, academyId, runId,
                ManagerRole.ESCORT, now);
        String escortToken = 토큰(escortAccountId, academyId, Role.ESCORT);

        mockMvc.perform(patch(UPDATE_STATUS.formatted(runId, riderId)).header("Authorization", escortToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusUpdateBody("boarded", "manual", UUID.randomUUID(), now)))
                .andExpect(status().isOk());
        entityManager.flush();

        // 새 PATCH 없이 revert 만 연달아 3회 — waiting↔boarded 를 오가며 매번 성공해야 한다(①응답 코드).
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post(REVERT.formatted(runId, riderId)).header("Authorization", escortToken)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isOk());
            entityManager.flush();
        }

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM rider_status_history WHERE run_rider_id = ?",
                Integer.class, riderId)).as("②이력 행 수 — 최초 처리 1건 + 되돌리기 3건, 제한 없이 전부 쌓여야 한다")
                .isEqualTo(4);
    }

    // ── 목표 6b·6c(R14-T2, Ruling 307) — 승하차지를 떠난 뒤에는 되돌리기 거부 ────────────

    @Test
    @DisplayName("목표6b(Ruling 307) — departed_at 이 기록된 승하차지는 되돌리기가 409 STOP_ALREADY_DEPARTED 이고 저장값은 그대로다")
    void 출발_처리된_승하차지는_되돌리기가_409이고_저장값은_불변이다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long academyId = fixtures().academy();
        long busId = fixtures().bus(academyId);
        long myStopId = fixtures().stop(academyId, "37.500000", "127.000000");
        long studentId = fixtures().student(academyId, "학생7");
        long runId = fixtures().movingRun(academyId, busId, now.minusMinutes(10), now.minusMinutes(40));
        long riderId = fixtures().runRider(runId, studentId, myStopId);
        fixtures().confirmedRunStop(runId, myStopId, now.minusMinutes(30));
        fixtures().departStop(runId, myStopId, now.minusMinutes(5));
        long escortAccountId = fixtures().assignedManager(managerRepository, assignmentRepository, academyId, runId,
                ManagerRole.ESCORT, now);

        mockMvc.perform(post(REVERT.formatted(runId, riderId))
                        .header("Authorization", 토큰(escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("STOP_ALREADY_DEPARTED"));

        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM run_rider WHERE id = ?", String.class, riderId))
                .as("①거부됐으니 최초 상태(waiting) 그대로여야 한다").isEqualTo("waiting");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM rider_status_history WHERE run_rider_id = ?",
                Integer.class, riderId)).as("②이력 행도 남지 않아야 한다").isEqualTo(0);
    }

    @Test
    @DisplayName("목표6b(Ruling 307) — 확정 노선이 있어도 아직 출발 처리 전이면 되돌리기는 그대로 성공한다")
    void 출발_처리_전이면_확정_노선이_있어도_되돌리기가_성공한다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long academyId = fixtures().academy();
        long busId = fixtures().bus(academyId);
        long myStopId = fixtures().stop(academyId, "37.500000", "127.000000");
        long studentId = fixtures().student(academyId, "학생8");
        long runId = fixtures().movingRun(academyId, busId, now.minusMinutes(10), now.minusMinutes(40));
        long riderId = fixtures().runRider(runId, studentId, myStopId);
        fixtures().confirmedRunStop(runId, myStopId, now.minusMinutes(30));
        long escortAccountId = fixtures().assignedManager(managerRepository, assignmentRepository, academyId, runId,
                ManagerRole.ESCORT, now);
        String escortToken = 토큰(escortAccountId, academyId, Role.ESCORT);

        mockMvc.perform(patch(UPDATE_STATUS.formatted(runId, riderId)).header("Authorization", escortToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusUpdateBody("boarded", "manual", UUID.randomUUID(), now)))
                .andExpect(status().isOk());
        entityManager.flush();

        mockMvc.perform(post(REVERT.formatted(runId, riderId)).header("Authorization", escortToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("waiting"));
    }

    @Test
    @DisplayName("목표6c(Ruling 307) — 뒤 순번이 없는 마지막 승하차지도 출발 처리되면 되돌리기가 거부된다(파생 규칙의 구멍이 막혔는지)")
    void 마지막_승하차지도_출발_처리되면_되돌리기가_거부된다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long academyId = fixtures().academy();
        long busId = fixtures().bus(academyId);
        long firstStopId = fixtures().stop(academyId, "37.500000", "127.000000");
        long lastStopId = fixtures().stop(academyId, "37.600000", "127.100000");
        long studentId = fixtures().student(academyId, "학생9");
        long runId = fixtures().movingRun(academyId, busId, now.minusMinutes(10), now.minusMinutes(40));
        // 이 학생의 승하차지는 노선의 마지막(seq=2) — 그 뒤에는 참조할 정차 항목이 존재하지 않는다.
        long riderId = fixtures().runRider(runId, studentId, lastStopId);
        fixtures().confirmedRunStop(runId, firstStopId, now.minusMinutes(30));
        fixtures().addRunStop(runId, lastStopId, 2, now.minusMinutes(20));
        fixtures().departStop(runId, lastStopId, now.minusMinutes(5));
        long escortAccountId = fixtures().assignedManager(managerRepository, assignmentRepository, academyId, runId,
                ManagerRole.ESCORT, now);

        mockMvc.perform(post(REVERT.formatted(runId, riderId))
                        .header("Authorization", 토큰(escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("STOP_ALREADY_DEPARTED"));
    }

    // ── R15-T3(Ruling 308) — 출발 전 되돌리기는 알림 없음, 출발 후 확정 결과 1건 ─────────

    /**
     * docs/archive/rounds/be-rounds-r15-r21.md §8.23 T3 목표 1·3·4 — 출발 전에는 boarded→revert→boarded 를 반복해도 알림이 전혀 나가지
     * 않고(정정 알림도 없음, Ruling 219 대체), 출발 시점에야 <b>마지막 상태</b>로 학생당 1건만
     * 적재된다. Phase 9 원본 {@code 되돌리면_원본_알림은_그대로_두고_취소_알림이_새로_적재된다}를
     * Ruling 308 에 맞게 다시 썼다.
     */
    @Test
    @DisplayName("목표1·3·4(Ruling 308) — 출발 전 되돌리기를 반복해도 알림이 없고, 출발 시점에 마지막 상태로 1건만 적재된다")
    void 출발_전_되돌리기를_반복해도_알림이_없고_출발_시점에_마지막_상태로_1건만_적재된다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long academyId = fixtures().academy();
        long busId = fixtures().bus(academyId);
        long stopId = fixtures().stop(academyId, "37.500000", "127.000000");
        long studentId = fixtures().student(academyId, "학생7");
        BoardingCommandFixtures.GuardianAccount guardian = fixtures().guardian(academyId, "보호자7");
        fixtures().linkChild(guardian.guardianId(), studentId, now.minusDays(1));
        long runId = fixtures().movingRun(academyId, busId, now.minusMinutes(10), now.minusMinutes(40));
        fixtures().confirmedRunStop(runId, stopId, now.minusMinutes(30));
        long riderId = fixtures().runRider(runId, studentId, stopId);
        // 목표 9 — waiting 인 채 출발하는 학생(같은 정차지, 알림 대상이 아니어야 한다).
        long waitingStudentId = fixtures().student(academyId, "학생7-대기");
        BoardingCommandFixtures.GuardianAccount waitingGuardian = fixtures().guardian(academyId, "보호자7-대기");
        fixtures().linkChild(waitingGuardian.guardianId(), waitingStudentId, now.minusDays(1));
        fixtures().runRider(runId, waitingStudentId, stopId);
        long escortAccountId = fixtures().assignedManager(managerRepository, assignmentRepository, academyId, runId,
                ManagerRole.ESCORT, now);
        String escortToken = 토큰(escortAccountId, academyId, Role.ESCORT);

        mockMvc.perform(patch(UPDATE_STATUS.formatted(runId, riderId)).header("Authorization", escortToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusUpdateBody("boarded", "manual", UUID.randomUUID(), now)))
                .andExpect(status().isOk());
        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_log WHERE recipient_account_id = ?", Integer.class,
                guardian.accountId())).as("①승차 직후에도 알림 미발행(목표1)").isEqualTo(0);

        mockMvc.perform(post(REVERT.formatted(runId, riderId)).header("Authorization", escortToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("waiting"));
        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_log WHERE recipient_account_id = ?", Integer.class,
                guardian.accountId())).as("②되돌린 뒤에도 정정 알림 미발행(목표4)").isEqualTo(0);

        mockMvc.perform(patch(UPDATE_STATUS.formatted(runId, riderId)).header("Authorization", escortToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusUpdateBody("boarded", "manual", UUID.randomUUID(), now)))
                .andExpect(status().isOk());
        entityManager.flush();

        출발_처리(runId, academyId, stopId, now.plusMinutes(1));
        entityManager.flush();

        List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT type, body, student_id, student_name FROM notification_log WHERE recipient_account_id = ? "
                        + "ORDER BY id",
                guardian.accountId());
        assertThat(rows).as("③출발 시점에 마지막 상태로 정확히 1건").hasSize(1);
        assertThat(rows.get(0).get("type")).as("④마지막 상태는 boarded 다").isEqualTo("boarding");
        assertThat(rows.get(0).get("body")).isEqualTo("학생7 학생이 버스에 탑승했습니다.");
        assertThat(rows.get(0).get("student_id")).isEqualTo(studentId);
        assertThat(rows.get(0).get("student_name")).isEqualTo("학생7");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_log WHERE type IN ('boarding_canceled', 'alighting_canceled')",
                Integer.class)).as("⑤취소 정정 알림 종류는 이제 적재되지 않는다(목표4)").isEqualTo(0);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_log WHERE recipient_account_id = ?", Integer.class,
                waitingGuardian.accountId())).as("⑥waiting 인 채 출발한 학생은 발송 대상이 아니다(목표9)").isEqualTo(0);
    }

    // ── BR-009 — 미승차를 되돌리면 케이스·미정차 표시도 함께 풀린다 ──────────────────

    /**
     * 미승차 → 되돌리기 → 다시 미승차. 되돌리기가 케이스를 열어 둔 채 두면 ①되돌린 학생이 대기 만료 뒤
     * 에스컬레이션되고 ②유일한 탑승자였던 승하차지가 {@code skipped} 로 남으며 ③두 번째 미승차가
     * {@code uk_no_show_case_run_rider} 위반으로 500 이 된다.
     */
    @Test
    @DisplayName("BR-009 — 미승차를 되돌리면 케이스가 종결되고 미정차가 풀리며, 다시 미승차하면 200 으로 같은 케이스가 재개된다")
    void 미승차를_되돌린_뒤_다시_미승차하면_200이고_같은_케이스가_재개된다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long academyId = fixtures().academy();
        long busId = fixtures().bus(academyId);
        long stopId = fixtures().stop(academyId, "37.500000", "127.000000");
        long studentId = fixtures().student(academyId, "학생-BR009");
        long runId = fixtures().movingRun(academyId, busId, now.minusMinutes(10), now.minusMinutes(40));
        long runStopId = fixtures().confirmedRunStop(runId, stopId, now.minusMinutes(30));
        long riderId = fixtures().runRider(runId, studentId, stopId);
        long escortAccountId = fixtures().assignedManager(managerRepository, assignmentRepository, academyId, runId,
                ManagerRole.ESCORT, now);
        String escortToken = 토큰(escortAccountId, academyId, Role.ESCORT);

        mockMvc.perform(patch(UPDATE_STATUS.formatted(runId, riderId)).header("Authorization", escortToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusUpdateBody("no_show", "manual", UUID.randomUUID(), now)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stop_skipped").value(true));
        entityManager.flush();
        Long caseId = jdbcTemplate.queryForObject("SELECT id FROM no_show_case WHERE run_rider_id = ?", Long.class,
                riderId);

        mockMvc.perform(post(REVERT.formatted(runId, riderId)).header("Authorization", escortToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("waiting"));
        entityManager.flush();

        assertThat(jdbcTemplate.queryForObject("SELECT resolved_at IS NOT NULL FROM no_show_case WHERE id = ?",
                Boolean.class, caseId)).as("①되돌리면 케이스가 종결돼 에스컬레이션 대상에서 빠진다").isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT change FROM run_stop WHERE id = ?", String.class, runStopId))
                .as("②잔여가 다시 1명이 됐으니 미정차 표시가 풀린다").isNull();

        // 첫 케이스가 되돌리기 전에 이미 만료·에스컬레이션까지 갔던 경우 — 재개가 그 흔적을 지우는지 본다.
        jdbcTemplate.update("UPDATE no_show_case SET expires_at = ?, escalated_at = ? WHERE id = ?",
                now.minusMinutes(20), now.minusMinutes(15), caseId);
        entityManager.clear();

        mockMvc.perform(patch(UPDATE_STATUS.formatted(runId, riderId)).header("Authorization", escortToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusUpdateBody("no_show", "manual", UUID.randomUUID(), now)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.no_show_case.case_id").value(caseId));
        entityManager.flush();

        java.util.Map<String, Object> reopened = jdbcTemplate.queryForMap(
                "SELECT resolved_at, escalated_at, expires_at = ? AS restarted FROM no_show_case WHERE id = ?",
                now.plusMinutes(AcademySetting.DEFAULT_NO_SHOW_WAIT_MINUTES), caseId);
        assertThat(reopened.get("resolved_at")).as("③재개된 케이스는 다시 열린다").isNull();
        assertThat(reopened.get("escalated_at")).as("④이전 에스컬레이션 흔적이 지워진다").isNull();
        assertThat(reopened.get("restarted")).as("⑤대기 카운트다운이 두 번째 미승차 시점부터 다시 시작된다")
                .isEqualTo(true);
    }

    // ── BR-078 — 멱등 재생은 같은 탑승자·같은 상태일 때만 ───────────────────────────

    @Test
    @DisplayName("BR-078 — 다른 탑승자 처리에 이미 쓰인 client_key 를 재사용하면 422 이고 새 처리는 저장되지 않는다")
    void 다른_탑승자에_쓰인_client_key_를_재사용하면_422다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long academyId = fixtures().academy();
        long busId = fixtures().bus(academyId);
        long stopId = fixtures().stop(academyId, "37.500000", "127.000000");
        long runId = fixtures().movingRun(academyId, busId, now.minusMinutes(10), now.minusMinutes(40));
        long riderA = fixtures().runRider(runId, fixtures().student(academyId, "학생-BR078-A"), stopId);
        long riderB = fixtures().runRider(runId, fixtures().student(academyId, "학생-BR078-B"), stopId);
        long escortAccountId = fixtures().assignedManager(managerRepository, assignmentRepository, academyId, runId,
                ManagerRole.ESCORT, now);
        String escortToken = 토큰(escortAccountId, academyId, Role.ESCORT);
        UUID sharedKey = UUID.randomUUID();

        mockMvc.perform(patch(UPDATE_STATUS.formatted(runId, riderA)).header("Authorization", escortToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusUpdateBody("boarded", "manual", sharedKey, now)))
                .andExpect(status().isOk());
        entityManager.flush();

        mockMvc.perform(patch(UPDATE_STATUS.formatted(runId, riderB)).header("Authorization", escortToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusUpdateBody("boarded", "manual", sharedKey, now)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mockMvc.perform(patch(UPDATE_STATUS.formatted(runId, riderA)).header("Authorization", escortToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusUpdateBody("no_show", "manual", sharedKey, now)))
                .andExpect(status().isUnprocessableContent());
        entityManager.flush();

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM run_rider WHERE id = ?", String.class, riderB))
                .as("B 의 처리는 A 의 결과로 대체된 적 없이 그대로다").isEqualTo("waiting");
    }

    // ── BR-031 — 하원 종료 보류 회차는 마지막 탑승자가 미승차로 빠져도 끝난다 ─────────────

    /**
     * 최종 지점 도착 때 잔류가 있어 종료가 보류된 하원 회차({@code finish_pending}). 자동 승차로 잘못
     * {@code boarded} 가 된 마지막 학생을 동승자가 [되돌리기]→[미승차] 로 처리하면 {@code boarded} 가 0명이
     * 되는데, 종료 판정이 하차 경로에만 걸려 있으면 회차가 {@code moving} 에 멈춘다(C-15).
     */
    @Test
    @DisplayName("BR-031 — 하원 종료 보류 회차의 마지막 탑승자를 되돌린 뒤 미승차 처리하면 회차가 종료된다")
    void 하원_종료_보류_회차의_마지막_탑승자를_미승차_처리하면_회차가_종료된다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long academyId = fixtures().academy();
        long busId = fixtures().bus(academyId);
        long stopId = fixtures().stop(academyId, "37.500000", "127.000000");
        long studentId = fixtures().student(academyId, "학생-BR031");
        long runId = fixtures().movingRun(academyId, busId, now.minusMinutes(10), now.minusMinutes(40));
        long riderId = fixtures().runRider(runId, studentId, stopId);
        long escortAccountId = fixtures().assignedManager(managerRepository, assignmentRepository, academyId, runId,
                ManagerRole.ESCORT, now);
        String escortToken = 토큰(escortAccountId, academyId, Role.ESCORT);

        mockMvc.perform(patch(UPDATE_STATUS.formatted(runId, riderId)).header("Authorization", escortToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusUpdateBody("boarded", "manual", UUID.randomUUID(), now)))
                .andExpect(status().isOk());
        entityManager.flush();
        // 종료 보류는 하원에만 있다(API_SPEC §4.10) — 등원 회차에 보류를 켜면 성립할 수 없는 상태를 검사하게 된다.
        jdbcTemplate.update("UPDATE run SET finish_pending = true, direction = 'from_academy' WHERE id = ?", runId);
        entityManager.clear();

        mockMvc.perform(post(REVERT.formatted(runId, riderId)).header("Authorization", escortToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch(UPDATE_STATUS.formatted(runId, riderId)).header("Authorization", escortToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusUpdateBody("no_show", "manual", UUID.randomUUID(), now)))
                .andExpect(status().isOk());
        entityManager.flush();

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM run WHERE id = ?", String.class, runId))
                .as("boarded 0명이 됐으니 보류된 종료가 완성된다").isEqualTo("finished");
        assertThat(applicationEvents.stream(RunEndedEvent.class)).as("run_ended 재료 이벤트 1건").hasSize(1);
    }

    // ── Ruling 345(BR-031 1번 갈래) — §3.3 전이 표 밖은 409 RIDER_TRANSITION_NOT_ALLOWED ──

    /**
     * 표 밖 전이 4종 — 같은 상태 재요청(boarded→boarded) · 표에 없는 방향(alighted→boarded ·
     * no_show→alighted · boarded→no_show). FEATURE_SPEC §3.3 이 허용하는 것은
     * waiting→boarded · waiting→no_show · boarded→alighted 셋뿐이라, 그 밖은 되돌리기(§4.7)를
     * 먼저 거쳐야 한다 — 상태·이력 모두 그대로여야 한다.
     */
    @ParameterizedTest(name = "{0} → {1} 는 409 RIDER_TRANSITION_NOT_ALLOWED")
    @CsvSource({ "boarded, boarded", "alighted, boarded", "no_show, alighted", "boarded, no_show" })
    @DisplayName("Ruling345 — §3.3 전이 표 밖 요청은 409 RIDER_TRANSITION_NOT_ALLOWED 이고 상태·이력이 그대로다")
    void 전이_표_밖_요청은_409이고_상태가_그대로다(String fromStatus, String targetStatus) throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long academyId = fixtures().academy();
        long busId = fixtures().bus(academyId);
        long stopId = fixtures().stop(academyId, "37.500000", "127.000000");
        long studentId = fixtures().student(academyId, "학생-전이표밖");
        long runId = fixtures().movingRun(academyId, busId, now.minusMinutes(10), now.minusMinutes(40));
        long riderId = fixtures().runRider(runId, studentId, stopId);
        long escortAccountId = fixtures().assignedManager(managerRepository, assignmentRepository, academyId, runId,
                ManagerRole.ESCORT, now);
        entityManager.flush(); // clear() 전 flush 필수 — assignedManager 의 계정 연결 UPDATE 가 아직 미반영 상태라 지우면 사라진다.
        jdbcTemplate.update("UPDATE run_rider SET status = ? WHERE id = ?", fromStatus, riderId);
        entityManager.clear();

        mockMvc.perform(patch(UPDATE_STATUS.formatted(runId, riderId))
                        .header("Authorization", 토큰(escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusUpdateBody(targetStatus, "manual", UUID.randomUUID(), now)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RIDER_TRANSITION_NOT_ALLOWED"));

        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM run_rider WHERE id = ?", String.class, riderId))
                .as("①거부됐으니 상태가 그대로여야 한다").isEqualTo(fromStatus);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM rider_status_history WHERE run_rider_id = ?",
                Integer.class, riderId)).as("②이력 행이 새로 생기지 않는다").isEqualTo(0);
    }

    /**
     * 그 승하차지를 실제로 출발 처리한다(Ruling 308, R15-T3 시험 전용) — 근접 알림 스케줄러가 타는
     * 것과 같은 진입점({@link StopDepartureService#claimAndPublish})을 그대로 불러 {@code
     * StopDepartedEvent} 를 발행시킨다. {@code confirmedRunStop} 으로 만든 확정 노선이 있어야 한다.
     */
    private void 출발_처리(long runId, long academyId, long stopId, OffsetDateTime departedAt) {
        long versionId = confirmedRouteRepository.findById(runId).orElseThrow().getCurrentVersionId();
        var runStop = runStopRepository.findByRouteVersionIdAndStopId(versionId, stopId).orElseThrow();
        stopDepartureService.claimAndPublish(runStop, runId, academyId, departedAt);
    }

    private String statusUpdateBody(String status, String verifyMethod, UUID clientKey, OffsetDateTime occurredAt) {
        return "{\"status\":\"%s\",\"verify_method\":\"%s\",\"client_key\":\"%s\",\"occurred_at\":\"%s\"}"
                .formatted(status, verifyMethod, clientKey, occurredAt);
    }

    private String 토큰(long accountId, long academyId, Role role) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, role, AccountStatus.ACTIVE);
    }
}
