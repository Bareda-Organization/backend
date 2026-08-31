package src.backend.boarding.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

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
import src.backend.boarding.command.BoardingCommandFixtures;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

/**
 * 승하차 처리(§4.6)·되돌리기(§4.7) — Phase 9(T3) 목표 4·7·12·13·14. 목표 11(하원 자동 승차)은
 * {@link src.backend.boarding.command.AutoBoardingServiceTest} 가 별도로 다룬다 — 이 워크트리에는
 * T2 소유의 회차 시작 엔드포인트가 없어 {@link src.backend.boarding.command.AutoBoardingService} 를
 * 직접 호출해야 하고, 그 호출은 컨트롤러 계층을 거치지 않기 때문이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BoardingControllerTest {

    private static final String UPDATE_STATUS = "/api/v1/runs/%d/riders/%d";

    private static final String REVERT = "/api/v1/runs/%d/riders/%d/revert";

    @Autowired
    private MockMvc mockMvc;

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

    private BoardingCommandFixtures fixtures;

    @TestConfiguration
    static class FixedClockConfig {

        private static final Instant FIXED = Instant.parse("2030-04-01T03:00:00Z"); // 2030-04-01 12:00 KST

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED, ZoneId.of("Asia/Seoul"));
        }
    }

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
        long escortAccountId = fixtures().escortAccount(academyId);

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

    @Test
    @DisplayName("목표7 — no_show 처리하면 케이스가 생기고 학부모·관계자 양쪽에 알림이 적재된다")
    void no_show_처리하면_케이스와_알림_두_행이_생긴다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long academyId = fixtures().academy();
        long busId = fixtures().bus(academyId);
        long stopId = fixtures().stop(academyId, "37.500000", "127.000000");
        long studentId = fixtures().student(academyId, "학생3");
        BoardingCommandFixtures.GuardianAccount guardian = fixtures().guardian(academyId, "보호자3");
        fixtures().linkChild(guardian.guardianId(), studentId, now.minusDays(1));
        long staffAccountId = fixtures().staffAccount(academyId);
        long runId = fixtures().movingRun(academyId, busId, now.minusMinutes(10), now.minusMinutes(40));
        long riderId = fixtures().runRider(runId, studentId, stopId);
        long escortAccountId = fixtures().escortAccount(academyId);

        mockMvc.perform(patch(UPDATE_STATUS.formatted(runId, riderId))
                        .header("Authorization", 토큰(escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusUpdateBody("no_show", "manual", UUID.randomUUID(), now)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("no_show"))
                .andExpect(jsonPath("$.data.no_show_case.case_id").isNumber());

        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM run_rider WHERE id = ?", String.class, riderId))
                .as("①run_rider.status")
                .isEqualTo("no_show");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM no_show_case WHERE run_rider_id = ?",
                Integer.class, riderId)).as("②케이스 행").isEqualTo(1);
        // notification_log.student_id 는 이 outbox 경로가 채우지 않는 컬럼이다(NotificationDraft 에
        // 그 필드 자체가 없다) — 그래서 실제로 채워지는 recipient_account_id 로 수신자를 식별한다.
        List<String> recipientRoles = jdbcTemplate.queryForList(
                "SELECT recipient_role FROM notification_log WHERE recipient_account_id IN (?, ?) "
                        + "ORDER BY recipient_role",
                String.class, guardian.accountId(), staffAccountId);
        assertThat(recipientRoles).as("③알림 2행(학부모·관계자)").containsExactlyInAnyOrder("parent", "staff");
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
        long escortAccountId = fixtures().escortAccount(academyId);

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
        long escortAccountId = fixtures().escortAccount(academyId);

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
        long escortAccountId = fixtures().escortAccount(academyId);
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
        // student_id 컬럼은 채워지지 않는다(위 목표7 주석과 동일 사유) — recipient_account_id 로 대체.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_log WHERE recipient_account_id = ?", Integer.class,
                guardian.accountId())).as("④알림 건수").isEqualTo(1);
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
        long escortAccountId = fixtures().escortAccount(academyId);

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
        long escortAccountId = fixtures().escortAccount(academyId);
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

    private String statusUpdateBody(String status, String verifyMethod, UUID clientKey, OffsetDateTime occurredAt) {
        return "{\"status\":\"%s\",\"verify_method\":\"%s\",\"client_key\":\"%s\",\"occurred_at\":\"%s\"}"
                .formatted(status, verifyMethod, clientKey, occurredAt);
    }

    private String 토큰(long accountId, long academyId, Role role) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, role, AccountStatus.ACTIVE);
    }
}
