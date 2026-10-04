package src.backend.monitoring.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.DocumentContext;
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
import src.backend.observability.metrics.SchedulerHealthMetrics;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import testsupport.clock.FixedClock20300401Config;

/**
 * §6.18 {@code GET /admin/dashboard}(Ruling 801~804) — 고정 시계 2030-04-01 12:00 KST 가 "지금" 이다. 학원 필터가 걸리는 값은 시험마다 새로 만든 학원으로
 * 좁혀 읽고, 학원 필터가 걸리지 않는 값(로그인·차단 계정·시스템 상태)은 삽입 전후 <b>차이</b>로 검사해 다른 시험이 남긴 행에 흔들리지 않게 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(FixedClock20300401Config.class)
class AdminDashboardControllerTest {

    private static final String DASHBOARD = "/api/v1/admin/dashboard";

    private static final LocalDate TODAY = LocalDate.of(2030, 4, 1);

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2030-04-01T12:00:00+09:00");

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private SchedulerHealthMetrics schedulerHealthMetrics;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private AcademyRepository academyRepository;

    @PersistenceContext
    private EntityManager entityManager;

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
    private ConfirmedRouteRepository confirmedRouteRepository;

    @Autowired
    private RouteVersionRepository routeVersionRepository;

    @Autowired
    private RunStopRepository runStopRepository;

    @Autowired
    private RunRiderRepository runRiderRepository;

    @Autowired
    private ManagerRepository managerRepository;

    @Autowired
    private AssignmentRepository assignmentRepository;

    private AdminMonitoringFixtures f() {
        return new AdminMonitoringFixtures(academyRepository, academyStaffRepository, busRepository, stopRepository,
                studentRepository, accountRepository, guardianRepository, guardianStudentRepository, runRepository,
                confirmedRouteRepository, routeVersionRepository, runStopRepository, runRiderRepository,
                managerRepository, assignmentRepository);
    }

    // ── 호출 도우미 ─────────────────────────────────────────────────

    private String 토큰(long accountId) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, null, Role.SYSTEM_ADMIN, AccountStatus.ACTIVE);
    }

    /** JDBC 로 바꾼 상태를 서비스의 엔티티 읽기가 보게 영속성 컨텍스트를 비운다. */
    private DocumentContext 대시보드(String query) throws Exception {
        entityManager.flush();
        entityManager.clear();
        long adminId = f().systemAdminAccount("메인관리자");
        return JsonPath.parse(mockMvc.perform(get(DASHBOARD + query).header("Authorization", 토큰(adminId)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private DocumentContext 학원_대시보드(long academyId, int days) throws Exception {
        return 대시보드("?days=%d&academy_id=%d".formatted(days, academyId));
    }

    /** 회차 한 건 — 상태·시각은 SQL 로 직접 맞춘다. 차량은 회차마다 새로 만들어 유일성(차량·날짜·방향·출발)을 피한다. */
    private long 회차(long academyId, LocalDate date, String status, OffsetDateTime startedAt, boolean canceled) {
        long busId = f().bus(academyId);
        OffsetDateTime depart = date.atTime(8, 0).atOffset(KST);
        long runId = runRepository.save(Run.forSchedule(academyId, busId, null, date, Direction.TO_ACADEMY, depart,
                depart.minusMinutes(30), "출발지", "도착지", 40)).getId();
        jdbcTemplate.update("UPDATE run SET status = ?, confirmed_at = ?, started_at = ?, finished_at = ? WHERE id = ?",
                status, "idle".equals(status) ? null : depart.minusMinutes(30), startedAt,
                "finished".equals(status) ? depart.plusHours(1) : null, runId);
        if (canceled) {
            jdbcTemplate.update("UPDATE run SET canceled_at = ?, cancel_source = 'staff' WHERE id = ?", depart.minusHours(1),
                    runId);
        }
        return runId;
    }

    private OffsetDateTime depart(LocalDate date) {
        return date.atTime(8, 0).atOffset(KST);
    }

    private void 지연_알림(long runId, long accountId, int minutes, OffsetDateTime sentAt) {
        jdbcTemplate.update("""
                INSERT INTO delay_notice (run_id, sent_by_account_id, minutes, reason, sent_at)
                VALUES (?, ?, ?, 'traffic', ?)
                """, runId, accountId, minutes, sentAt);
    }

    private void 변경요청(long academyId, long runId, long studentId, String status, OffsetDateTime requestedAt,
            OffsetDateTime deadlineAt, OffsetDateTime decidedAt) {
        jdbcTemplate.update("""
                INSERT INTO change_request (academy_id, run_id, student_id, source, type, status, window_segment,
                                            requested_by, requested_at, deadline_at, decided_at, reject_reason)
                VALUES (?, ?, ?, 'change_request', 'cancel', ?, 2, 1, ?, ?, ?, ?)
                """, academyId, runId, studentId, status, requestedAt, deadlineAt, decidedAt,
                "rejected".equals(status) ? "사유" : null);
    }

    private void 로그인_행(String action, OffsetDateTime occurredAt, Long actorAccountId) {
        jdbcTemplate.update("""
                INSERT INTO audit_log (actor_account_id, actor_login_id, category, action, occurred_at)
                VALUES (?, 'dash-user', 'login', ?, ?)
                """, actorAccountId, action, occurredAt);
    }

    /** 계정을 차단 상태로 — 제약({@code ck_account_status_before_block_pair})상 차단 전 상태가 짝으로 있어야 한다. */
    private void 차단(long accountId) {
        jdbcTemplate.update("UPDATE account SET status = 'blocked', status_before_block = 'active', blocked_at = ? WHERE id = ?",
                NOW.minusMinutes(30), accountId);
    }

    private long 값(DocumentContext json, String path) {
        return ((Number) json.read(path)).longValue();
    }

    // ── ① 검증 · 권한 ───────────────────────────────────────────────

    @Test
    @DisplayName("① days 가 1·7·30 밖이면 422, 없는 academy_id 는 404, 메인 관리자가 아니면 403")
    void 요청_검증과_권한() throws Exception {
        long adminId = f().systemAdminAccount("메인관리자");
        for (String days : new String[] { "0", "2", "31", "-1" }) {
            mockMvc.perform(get(DASHBOARD + "?days=" + days).header("Authorization", 토큰(adminId)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
        mockMvc.perform(get(DASHBOARD + "?academy_id=999999999").header("Authorization", 토큰(adminId)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("ACADEMY_NOT_FOUND"));
        for (String days : new String[] { "1", "7", "30" }) {
            mockMvc.perform(get(DASHBOARD + "?days=" + days).header("Authorization", 토큰(adminId)))
                    .andExpect(status().isOk());
        }
        long academyId = f().academy();
        long staffId = f().staffAccount(academyId, "관계자");
        mockMvc.perform(get(DASHBOARD).header("Authorization",
                        "Bearer " + tokenProvider.createAccessToken(staffId, academyId, Role.STAFF, AccountStatus.ACTIVE)))
                .andExpect(status().isForbidden());
    }

    // ── ② 정시 출발률 · ③ 직전 기간 · ④ 지연 회차 ────────────────────────

    @Test
    @DisplayName("② 정시 출발은 +5분까지(정확히 +5분은 정시, +5분 1초는 아님) · 분모 0 이면 null · 취소 회차 제외")
    void 정시_출발률_경계() throws Exception {
        long academyId = f().academy();
        OffsetDateTime depart = depart(TODAY);
        회차(academyId, TODAY, "moving", depart, false); // 정각
        회차(academyId, TODAY, "moving", depart.plusMinutes(5), false); // 정확히 +5분 — 정시
        회차(academyId, TODAY, "moving", depart.plusMinutes(5).plusSeconds(1), false); // +5분 1초 — 아님
        회차(academyId, TODAY, "moving", depart.plusMinutes(1), true); // 취소 — 제외
        회차(academyId, TODAY, "confirmed", null, false); // 아직 시작 안 함 — 분모에 안 든다
        long emptyAcademy = f().academy();

        DocumentContext json = 학원_대시보드(academyId, 7);

        assertThat(값(json, "$.data.on_time.started_count")).isEqualTo(3);
        assertThat(값(json, "$.data.on_time.on_time_count")).isEqualTo(2);
        assertThat(((Number) json.read("$.data.on_time.rate")).doubleValue()).isEqualTo(2.0 / 3.0);
        assertThat(((Number) json.read("$.data.on_time.target_rate")).doubleValue()).isEqualTo(0.9);
        assertThat(값(json, "$.data.runs.count")).isEqualTo(4); // 미취소 4건(시작 안 한 회차 포함)
        assertThat(값(json, "$.data.runs.canceled_count")).isEqualTo(1);
        assertThat(json.<Object>read("$.data.academies[0].on_time_rate")).isNotNull();

        DocumentContext empty = 학원_대시보드(emptyAcademy, 7);
        assertThat(empty.<Object>read("$.data.on_time.rate")).isNull();
        assertThat(empty.<Object>read("$.data.academies[0].on_time_rate")).isNull();
        assertThat(값(empty, "$.data.on_time.started_count")).isZero();
    }

    @Test
    @DisplayName("③ 직전 기간 previous_count 는 바로 앞 같은 길이 구간의 미취소 회차 수")
    void 직전_기간_회차_수() throws Exception {
        long academyId = f().academy();
        회차(academyId, TODAY.minusDays(6), "finished", depart(TODAY.minusDays(6)), false); // 이번 기간 첫날(7일)
        회차(academyId, TODAY.minusDays(7), "finished", depart(TODAY.minusDays(7)), false); // 직전 기간 끝날
        회차(academyId, TODAY.minusDays(7), "finished", depart(TODAY.minusDays(7)), false);
        회차(academyId, TODAY.minusDays(13), "finished", depart(TODAY.minusDays(13)), false); // 직전 기간 첫날
        회차(academyId, TODAY.minusDays(14), "finished", depart(TODAY.minusDays(14)), false); // 둘 다 아님
        회차(academyId, TODAY.minusDays(8), "idle", null, true); // 직전 기간이지만 취소 — 안 센다

        DocumentContext json = 학원_대시보드(academyId, 7);

        assertThat(값(json, "$.data.runs.count")).isEqualTo(1);
        assertThat(값(json, "$.data.runs.previous_count")).isEqualTo(3);
        assertThat(json.<String>read("$.data.period.from")).isEqualTo("2030-03-26");
        assertThat(json.<String>read("$.data.period.to")).isEqualTo("2030-04-01");
        assertThat(json.<Integer>read("$.data.period.days")).isEqualTo(7);
    }

    @Test
    @DisplayName("④ 지연 회차는 알림이 여러 건이어도 1회 · 지연이 없으면 peak 는 null")
    void 지연_회차는_알림_수와_무관하게_한_번() throws Exception {
        long academyId = f().academy();
        long senderId = f().staffAccount(academyId, "발신자");
        long delayed = 회차(academyId, TODAY, "moving", depart(TODAY), false);
        지연_알림(delayed, senderId, 5, NOW.minusMinutes(30));
        지연_알림(delayed, senderId, 10, NOW.minusMinutes(20));
        지연_알림(delayed, senderId, 15, NOW.minusMinutes(10));
        long yesterday = 회차(academyId, TODAY.minusDays(1), "finished", depart(TODAY.minusDays(1)), false);
        지연_알림(yesterday, senderId, 5, NOW.minusDays(1));
        long quiet = f().academy();

        DocumentContext json = 학원_대시보드(academyId, 7);

        assertThat(값(json, "$.data.delays.count")).isEqualTo(2);
        assertThat(값(json, "$.data.delays.today_count")).isEqualTo(1);
        assertThat(json.<String>read("$.data.delays.peak.date")).isIn("2030-04-01", "2030-03-31");
        assertThat(값(json, "$.data.delays.peak.count")).isEqualTo(1);
        assertThat(값(json, "$.data.academies[0].delay_count")).isEqualTo(2);
        assertThat(학원_대시보드(quiet, 7).<Object>read("$.data.delays.peak")).isNull();
    }

    // ── ⑤ 변경 요청 결정 ──────────────────────────────────────────

    @Test
    @DisplayName("⑤ 변경 요청 결정 3분포는 기간에 결정된 것만, 접수 수는 기간에 접수된 것")
    void 변경_요청_결정_분포() throws Exception {
        long academyId = f().academy();
        long studentId = f().student(academyId, "학생", "010-0000-0000", null);
        long runId = 회차(academyId, TODAY, "confirmed", null, false);
        OffsetDateTime inPeriod = NOW.minusDays(1);
        변경요청(academyId, runId, studentId, "approved", inPeriod.minusHours(1), null, inPeriod);
        변경요청(academyId, runId, studentId, "approved", inPeriod.minusHours(1), null, inPeriod);
        변경요청(academyId, runId, studentId, "rejected", inPeriod.minusHours(1), null, inPeriod);
        변경요청(academyId, runId, studentId, "auto_rejected", inPeriod.minusHours(1), null, inPeriod);
        변경요청(academyId, runId, studentId, "pending", inPeriod, NOW.plusHours(2), null); // 결정 전 — 분포에 안 든다
        변경요청(academyId, runId, studentId, "approved", NOW.minusDays(20), null, NOW.minusDays(20)); // 기간 밖

        DocumentContext json = 학원_대시보드(academyId, 7);

        assertThat(값(json, "$.data.change_requests.approved")).isEqualTo(2);
        assertThat(값(json, "$.data.change_requests.rejected")).isEqualTo(1);
        assertThat(값(json, "$.data.change_requests.auto_rejected")).isEqualTo(1);
        assertThat(값(json, "$.data.change_requests.total")).isEqualTo(4);
        assertThat(값(json, "$.data.academies[0].change_request_count")).isEqualTo(5); // 기간에 접수된 5건(기간 밖 1건 제외)
    }

    // ── ⑥ 로그인 · ⑦ daily · ⑧ 학원 필터 ─────────────────────────────

    @Test
    @DisplayName("⑥ 로그인 성공·실패·차단·해제 집계 — audit_log 로그인 행, 서울 날짜 기준(삽입 전후 차이)")
    void 로그인_집계() throws Exception {
        DocumentContext before = 대시보드("?days=1");
        long blockedNow = f().systemAdminAccount("차단중");
        차단(blockedNow);
        long releasedLater = f().systemAdminAccount("풀린계정");
        로그인_행("login_success", NOW.minusHours(1), blockedNow);
        로그인_행("login_success", NOW.minusHours(2), blockedNow);
        로그인_행("login_fail", NOW.minusHours(3), blockedNow);
        로그인_행("login_success", NOW.minusDays(1), blockedNow); // 어제 — 오늘/어제 칸
        로그인_행("login_fail", NOW.minusDays(3), blockedNow); // days=1 기간 밖
        로그인_행("block", NOW.minusMinutes(30), blockedNow); // 지금도 차단
        로그인_행("block", NOW.minusMinutes(40), releasedLater); // 이미 풀림(상태 active)
        로그인_행("unblock", NOW.minusMinutes(20), releasedLater); // 해제 행은 시도가 아니다

        DocumentContext after = 대시보드("?days=1");

        assertThat(값(after, "$.data.logins.success") - 값(before, "$.data.logins.success")).isEqualTo(2);
        assertThat(값(after, "$.data.logins.fail") - 값(before, "$.data.logins.fail")).isEqualTo(1);
        assertThat(값(after, "$.data.logins.today_success") - 값(before, "$.data.logins.today_success")).isEqualTo(2);
        assertThat(값(after, "$.data.logins.yesterday_success") - 값(before, "$.data.logins.yesterday_success"))
                .isEqualTo(1);
        assertThat(값(after, "$.data.logins.blocks") - 값(before, "$.data.logins.blocks")).isEqualTo(2);
        assertThat(값(after, "$.data.logins.blocks_released") - 값(before, "$.data.logins.blocks_released")).isEqualTo(1);
    }

    @Test
    @DisplayName("⑦ daily[] 는 days=1 이어도 7칸(오름차순), days=30 이면 30칸 — 날짜별 회차·지연·로그인")
    void 일별_추이_칸수() throws Exception {
        long academyId = f().academy();
        회차(academyId, TODAY, "moving", depart(TODAY), false);
        회차(academyId, TODAY.minusDays(2), "finished", depart(TODAY.minusDays(2)), false);
        DocumentContext before = 학원_대시보드(academyId, 1);
        로그인_행("login_success", NOW.minusHours(1), null);
        로그인_행("login_fail", NOW.minusDays(2), null);

        DocumentContext one = 학원_대시보드(academyId, 1);
        DocumentContext thirty = 학원_대시보드(academyId, 30);

        assertThat(one.<List<Object>>read("$.data.daily")).hasSize(7);
        assertThat(one.<String>read("$.data.daily[0].date")).isEqualTo("2030-03-26");
        assertThat(one.<String>read("$.data.daily[6].date")).isEqualTo("2030-04-01");
        assertThat(thirty.<List<Object>>read("$.data.daily")).hasSize(30);
        assertThat(값(one, "$.data.daily[6].run_count")).isEqualTo(1);
        assertThat(값(one, "$.data.daily[4].run_count")).isEqualTo(1); // 3월 30일
        assertThat(값(one, "$.data.daily[6].login_success") - 값(before, "$.data.daily[6].login_success")).isEqualTo(1);
        assertThat(값(one, "$.data.daily[4].login_fail") - 값(before, "$.data.daily[4].login_fail")).isEqualTo(1);
        assertThat(값(one, "$.data.runs.count")).as("기간은 오늘 하루 — 추이 칸이 늘어도 기간 합은 그대로").isEqualTo(1);
    }

    @Test
    @DisplayName("⑧ academy_id 필터는 logins·health·blocked_accounts 에는 걸리지 않는다")
    void 학원_필터가_걸리지_않는_값() throws Exception {
        long academyA = f().academy();
        long academyB = f().academy();
        long blocked = f().systemAdminAccount("차단계정");
        차단(blocked);
        로그인_행("login_success", NOW.minusHours(1), blocked);
        회차(academyA, TODAY, "finished", depart(TODAY), false);

        DocumentContext forA = 학원_대시보드(academyA, 7);
        DocumentContext forB = 학원_대시보드(academyB, 7);
        DocumentContext all = 대시보드("?days=7");

        assertThat(값(forA, "$.data.runs.count")).isEqualTo(1);
        assertThat(값(forB, "$.data.runs.count")).isZero();
        assertThat(forA.<Object>read("$.data.logins")).isEqualTo(forB.<Object>read("$.data.logins"))
                .isEqualTo(all.<Object>read("$.data.logins"));
        assertThat(forA.<Object>read("$.data.health")).isEqualTo(forB.<Object>read("$.data.health"));
        assertThat(값(forA, "$.data.attention.blocked_accounts")).isEqualTo(값(forB, "$.data.attention.blocked_accounts"))
                .isEqualTo(값(all, "$.data.attention.blocked_accounts")).isGreaterThanOrEqualTo(1);
        assertThat(forA.<List<Object>>read("$.data.academies")).hasSize(1);
    }

    // ── ⑨ attention ───────────────────────────────────────────────

    @Test
    @DisplayName("⑨ attention — 가입 막힘 · 지연 회차 · 마감 30분 안 변경 요청 묶음 · 미확인 비상 · 끝나지 않은 회차 · 확정 실패")
    void 처리할_것_목록() throws Exception {
        long academyId = f().academy();
        long otherAcademy = f().academy();
        long senderId = f().staffAccount(academyId, "재직관계자"); // 이 학원에는 재직 관계자가 있다 — 새 관계자 가입은 막힌다
        long applicant = f().systemAdminAccount("신청자");
        jdbcTemplate.update("""
                INSERT INTO signup_request (account_id, academy_id, requested_role, approver_type, status, requested_at)
                VALUES (?, ?, 'staff', 'system_admin', 'pending', ?)
                """, applicant, academyId, NOW.minusHours(2));
        long freeApplicant = f().systemAdminAccount("막힘없는신청자");
        jdbcTemplate.update("""
                INSERT INTO signup_request (account_id, academy_id, requested_role, approver_type, status, requested_at)
                VALUES (?, ?, 'staff', 'system_admin', 'pending', ?)
                """, freeApplicant, otherAcademy, NOW.minusHours(1)); // 재직 관계자가 없는 학원 — 막히지 않았다

        long delayedRun = 회차(academyId, TODAY, "moving", depart(TODAY).plusMinutes(6), false);
        지연_알림(delayedRun, senderId, 5, NOW.minusMinutes(5));
        지연_알림(delayedRun, senderId, 10, NOW.minusMinutes(2));
        회차(academyId, TODAY, "idle", null, false); // 확정 실패 후보
        jdbcTemplate.update("UPDATE run SET consecutive_failures = 3 WHERE academy_id = ? AND status = 'idle'", academyId);
        회차(academyId, TODAY.minusDays(3), "moving", depart(TODAY.minusDays(3)), false); // 끝나지 않은 회차(stale)

        long studentId = f().student(academyId, "학생", "010-0000-0000", null);
        long runX = 회차(academyId, TODAY, "confirmed", null, false);
        long runY = 회차(academyId, TODAY, "confirmed", null, false);
        long runZ = 회차(academyId, TODAY, "confirmed", null, false);
        변경요청(academyId, runX, studentId, "pending", NOW.minusHours(1), NOW.plusMinutes(10), null);
        변경요청(academyId, runX, studentId, "pending", NOW.minusHours(1), NOW.plusMinutes(20), null); // 같은 회차 — 한 묶음
        변경요청(academyId, runY, studentId, "pending", NOW.minusHours(1), NOW.plusMinutes(31), null); // 30분 밖
        변경요청(academyId, runZ, studentId, "pending", NOW.minusHours(1), NOW.plusMinutes(30), null); // 정확히 30분 — 안
        변경요청(academyId, runZ, studentId, "pending", NOW.minusHours(1), NOW.minusMinutes(1), null); // 이미 지남 — 제외

        emergency(academyId, delayedRun, null, null); // 미확인
        emergency(academyId, delayedRun, NOW, null); // 확인됨
        emergency(academyId, delayedRun, null, NOW); // 취소됨

        DocumentContext json = 학원_대시보드(academyId, 7);

        assertThat(json.<List<Object>>read("$.data.attention.signup_blocked")).hasSize(1);
        assertThat(json.<String>read("$.data.attention.signup_blocked[0].name")).isEqualTo("신청자");
        assertThat(json.<String>read("$.data.attention.signup_blocked[0].academy_name")).isNotBlank();
        assertThat(json.<Object>read("$.data.attention.signup_blocked[0].request_id")).isNotNull();
        assertThat(json.<List<Object>>read("$.data.attention.delayed_runs")).hasSize(1);
        assertThat(((Number) json.read("$.data.attention.delayed_runs[0].delay_minutes")).intValue()).isEqualTo(6);
        assertThat(json.<String>read("$.data.attention.delayed_runs[0].direction")).isEqualTo("to_academy");
        assertThat(json.<List<Object>>read("$.data.attention.expiring_change_requests")).hasSize(2);
        assertThat(json.<List<Number>>read("$.data.attention.expiring_change_requests[*].count"))
                .extracting(Number::longValue).containsExactly(2L, 1L);
        assertThat(OffsetDateTime.parse(json.read("$.data.attention.expiring_change_requests[0].deadline_at")).toInstant())
                .isEqualTo(NOW.plusMinutes(10).toInstant());
        assertThat(값(json, "$.data.attention.unacked_emergencies")).isEqualTo(1);
        assertThat(값(json, "$.data.attention.stale_runs")).isEqualTo(1);
        assertThat(값(json, "$.data.attention.confirm_failed_runs")).isEqualTo(1);
    }

    private void emergency(long academyId, long runId, OffsetDateTime ackedAt, OffsetDateTime canceledAt) {
        jdbcTemplate.update("""
                INSERT INTO emergency_alert (academy_id, run_id, bus_no, raised_by, raised_by_role, type, rider_count,
                                             occurred_at, received_at, client_key, acked_at, canceled_at)
                VALUES (?, ?, '1호차', 1, 'driver', 'accident', 3, ?, ?, ?, ?, ?)
                """, academyId, runId, NOW.minusMinutes(10), NOW.minusMinutes(10), UUID.randomUUID(), ackedAt, canceledAt);
    }

    // ── ⑩ today_runs ─────────────────────────────────────────────

    @Test
    @DisplayName("⑩ today_runs[] — 필드 · 출발 순 · 취소 제외 · 확정 전은 stops null · moving 만 지연 분")
    void 오늘_회차_필드() throws Exception {
        AdminMonitoringFixtures f = f();
        long academyId = f.academy();
        long stopA = f.stop(academyId, "37.500000", "127.000000");
        long stopB = f.stop(academyId, "37.501000", "127.001000");
        long movingId = f.movingRun(academyId, f.bus(academyId), Direction.TO_ACADEMY, NOW.minusMinutes(40),
                NOW.minusMinutes(70), NOW.minusMinutes(34), 40);
        long versionId = f.confirmedRouteWithVersion(movingId, NOW.minusMinutes(60));
        long arrived = f.runStopForStop(versionId, stopA, 1, NOW.minusMinutes(30));
        f.runStopForStop(versionId, stopB, 2, NOW.minusMinutes(20));
        jdbcTemplate.update("INSERT INTO run_stop (route_version_id, seq, destination) VALUES (?, 3, true)", versionId);
        f.markArrived(arrived, NOW.minusMinutes(26));
        var driver = f.manager(academyId, ManagerRole.DRIVER, "기사");
        f.assign(movingId, driver.managerId(), ManagerRole.DRIVER);
        long idleId = f.idleRun(academyId, f.bus(academyId), Direction.FROM_ACADEMY, NOW.plusHours(3));
        long canceledId = f.idleRun(academyId, f.bus(academyId), Direction.FROM_ACADEMY, NOW.plusHours(4));
        jdbcTemplate.update("UPDATE run SET canceled_at = ?, cancel_source = 'staff' WHERE id = ?", NOW, canceledId);
        long studentId = f.student(academyId, "학생", "010-0000-0000", null);
        변경요청(academyId, movingId, studentId, "pending", NOW.minusHours(1), NOW.plusHours(2), null);

        DocumentContext json = 학원_대시보드(academyId, 7);

        assertThat(json.<List<Object>>read("$.data.today_runs")).hasSize(2);
        assertThat(json.<String>read("$.data.today_runs[0].run_status")).isEqualTo("moving");
        assertThat(json.<String>read("$.data.today_runs[1].run_status")).isEqualTo("idle");
        assertThat(json.<List<Object>>read("$.data.today_runs[?(@.run_id == '%d')]".formatted(canceledId))).isEmpty();
        assertThat(((Number) json.read("$.data.today_runs[0].delay_minutes")).intValue())
                .as("도착한 정차가 있으면 출발 지연(6분)이 아니라 그 정차의 도착 − 예정(4분)이다").isEqualTo(4);
        assertThat(((Number) json.read("$.data.today_runs[0].stops_done")).intValue()).isEqualTo(1);
        assertThat(((Number) json.read("$.data.today_runs[0].stops_total")).intValue()).as("도착지·경유 제외").isEqualTo(2);
        assertThat(((Number) json.read("$.data.today_runs[0].pending_change_count")).intValue()).isEqualTo(1);
        assertThat((Boolean) json.read("$.data.today_runs[0].driver_assigned")).isTrue();
        assertThat(json.<String>read("$.data.today_runs[0].academy_name")).isNotBlank();
        assertThat(json.<String>read("$.data.today_runs[0].bus_no")).isNotBlank();
        assertThat(json.<Object>read("$.data.today_runs[0].started_at")).isNotNull();
        assertThat(OffsetDateTime.parse(json.read("$.data.today_runs[0].est_arrival_time")).toInstant())
                .isEqualTo(NOW.minusMinutes(40).plusMinutes(40).toInstant());
        assertThat(json.<Object>read("$.data.today_runs[1].delay_minutes")).isNull();
        assertThat(json.<Object>read("$.data.today_runs[1].stops_done")).isNull();
        assertThat(json.<Object>read("$.data.today_runs[1].stops_total")).isNull();
        assertThat((Boolean) json.read("$.data.today_runs[1].driver_assigned")).isFalse();
        assertThat(json.<Object>read("$.data.today_runs[1].est_arrival_time")).isNull();
        assertThat(idleId).isPositive();
    }

    // ── ⑪ health ─────────────────────────────────────────────────

    private String 칸(DocumentContext json, String key, String field) {
        List<String> values = json.read("$.data.health[?(@.key == '%s')].%s".formatted(key, field));
        return values.isEmpty() ? null : values.get(0);
    }

    @Test
    @DisplayName("⑪ health[] 4칸 — 정상이면 전부 ok, 위치 끊김은 warn, 발송 지연은 warn")
    void 시스템_상태_4칸() throws Exception {
        // 정상 기준선 — 다른 시험이 남긴 대기 알림을 걷고 확정 배치의 마지막 성공을 지금으로 맞춘다
        jdbcTemplate.update("UPDATE notification_log SET push_state = 'sent' WHERE push_state = 'pending'");
        schedulerHealthMetrics.recordSuccess(SchedulerHealthMetrics.nameOf(src.backend.run.scheduler.RunConfirmationScheduler.class));
        DocumentContext healthy = 대시보드("?days=1");
        assertThat(healthy.<List<Object>>read("$.data.health")).hasSize(4);
        for (String key : new String[] { "api", "position", "confirm_batch", "notification" }) {
            assertThat(칸(healthy, key, "status")).as(key).isEqualTo("ok");
            assertThat(healthy.<List<Object>>read("$.data.health[?(@.key == '%s')].detail".formatted(key))).containsExactly((Object) null);
        }

        // 위치 끊김 — 시작한 지 10분 된 운행 중 회차가 한 번도 위치를 안 보냈다
        long academyId = f().academy();
        long movingId = f().movingRun(academyId, f().bus(academyId), Direction.TO_ACADEMY, NOW.minusMinutes(20),
                NOW.minusMinutes(50), NOW.minusMinutes(10), 40);
        stringRedisTemplate.delete("run:%d:position".formatted(movingId));
        // 발송 지연 — 6분 전에 적재돼 아직 대기 중인 알림
        jdbcTemplate.update("""
                INSERT INTO notification_log (academy_id, recipient_account_id, recipient_name, recipient_role, type,
                                              title, body, dedup_key, created_at)
                VALUES (?, 1, '수신자', 'staff', 'delay', '지연', '본문', ?, ?)
                """, academyId, "dash-health-" + UUID.randomUUID(), NOW.minusMinutes(6));

        DocumentContext degraded = 대시보드("?days=1");

        assertThat(칸(degraded, "position", "status")).isEqualTo("warn");
        assertThat(칸(degraded, "position", "detail")).isEqualTo("위치 끊김 1대");
        assertThat(칸(degraded, "notification", "status")).isEqualTo("warn");
        assertThat(칸(degraded, "notification", "detail")).isEqualTo("발송 지연 1건");
        assertThat(칸(degraded, "api", "status")).isEqualTo("ok");
        assertThat(칸(degraded, "confirm_batch", "status")).isEqualTo("ok");
    }

    // ── ⑫ recent_events ──────────────────────────────────────────

    /** 회차 확정·출발·종료와 지연 알림·가입 신청을 한 학원에 만든다 — {@code delays} 건의 지연 알림(분 단위로 흩어)을 더한다. */
    private long 사건_준비(long academyId, int delays) {
        long senderId = f().staffAccount(academyId, "재직관계자");
        long applicant = f().systemAdminAccount("신청자");
        long runId = f().movingRun(academyId, f().bus(academyId), Direction.TO_ACADEMY, NOW.minusHours(3),
                NOW.minusHours(4), NOW.minusHours(2), 40);
        entityManager.flush(); // 미반영 엔티티 변경이 아래 JDBC 갱신을 나중에 덮어쓰지 않게 먼저 내보낸다
        jdbcTemplate.update("UPDATE run SET finished_at = ?, status = 'finished' WHERE id = ?", NOW.minusHours(1), runId);
        jdbcTemplate.update("""
                INSERT INTO signup_request (account_id, academy_id, requested_role, approver_type, status, requested_at)
                VALUES (?, ?, 'staff', 'system_admin', 'pending', ?)
                """, applicant, academyId, NOW.minusMinutes(50));
        for (int i = 1; i <= delays; i++) {
            지연_알림(runId, senderId, 5, NOW.minusMinutes(30 - i));
        }
        // 7일 밖 사건 — 싣지 않는다
        long oldRun = f().movingRun(academyId, f().bus(academyId), Direction.FROM_ACADEMY, NOW.minusDays(9),
                NOW.minusDays(9).minusHours(1), NOW.minusDays(9).plusMinutes(1), 40);
        entityManager.flush();
        jdbcTemplate.update("UPDATE run SET service_date = ? WHERE id = ?", TODAY.minusDays(9), oldRun);
        return runId;
    }

    @Test
    @DisplayName("⑫ recent_events[] — 5종이 전부 나오고 최근 7일 · 시각 내림차순 · 해당 없는 키는 null")
    void 최근_기록_5종() throws Exception {
        long academyId = f().academy();
        사건_준비(academyId, 4);

        DocumentContext json = 학원_대시보드(academyId, 7);

        List<String> kinds = json.read("$.data.recent_events[*].kind");
        List<String> at = json.read("$.data.recent_events[*].at");
        assertThat(kinds).hasSize(8).containsOnly("run_confirmed", "run_started", "run_finished", "delay_notified",
                "staff_signup_requested");
        assertThat(at).isSortedAccordingTo(java.util.Comparator.<String, OffsetDateTime>comparing(OffsetDateTime::parse,
                java.util.Comparator.comparing(OffsetDateTime::toInstant)).reversed());
        assertThat(kinds.get(0)).isEqualTo("delay_notified");
        assertThat(kinds.get(7)).isEqualTo("run_confirmed");
        assertThat(json.<Integer>read("$.data.recent_events[0].delay_minutes")).isEqualTo(5);
        assertThat(json.<String>read("$.data.recent_events[0].bus_no")).isNotBlank();
        assertThat(json.<String>read("$.data.recent_events[0].direction")).isEqualTo("to_academy");
        assertThat(json.<Object>read("$.data.recent_events[0].name")).isNull();
        int signupIndex = kinds.indexOf("staff_signup_requested");
        assertThat(json.<String>read("$.data.recent_events[%d].name".formatted(signupIndex))).isEqualTo("신청자");
        assertThat(json.<String>read("$.data.recent_events[%d].status".formatted(signupIndex))).isEqualTo("pending");
        assertThat(json.<Object>read("$.data.recent_events[%d].run_id".formatted(signupIndex))).isNull();
        assertThat(json.<Object>read("$.data.recent_events[%d].delay_minutes".formatted(signupIndex))).isNull();
    }

    @Test
    @DisplayName("⑫ 사건이 10건을 넘으면 최신 10건만 싣는다")
    void 최근_기록은_최신_10건() throws Exception {
        long academyId = f().academy();
        사건_준비(academyId, 9);

        DocumentContext json = 학원_대시보드(academyId, 7);

        List<String> kinds = json.read("$.data.recent_events[*].kind");
        assertThat(kinds).hasSize(10);
        // 9건의 지연 알림 + 가입 신청(50분 전) 1건이 최신 10건이고, 1시간 전 종료·2시간 전 출발·4시간 전 확정은 잘린다
        assertThat(kinds.stream().filter("delay_notified"::equals).count()).isEqualTo(9);
        assertThat(kinds.get(9)).isEqualTo("staff_signup_requested");
    }

    @Test
    @DisplayName("⑫ 확정 사건은 지금 그 회차의 대기 변경 요청 수를 싣고, 7일 밖 사건은 싣지 않는다")
    void 확정_사건은_대기_변경_요청_수를_싣는다() throws Exception {
        long academyId = f().academy();
        long runId = f().confirmedRun(academyId, f().bus(academyId), Direction.TO_ACADEMY, NOW.plusMinutes(10),
                NOW.minusMinutes(20));
        long studentId = f().student(academyId, "학생", "010-0000-0000", null);
        변경요청(academyId, runId, studentId, "pending", NOW.minusHours(1), NOW.plusHours(1), null);
        변경요청(academyId, runId, studentId, "pending", NOW.minusHours(1), NOW.plusHours(1), null);
        long oldRun = f().confirmedRunOn(academyId, f().bus(academyId), Direction.FROM_ACADEMY, NOW.minusDays(9),
                NOW.minusDays(9).minusHours(1), TODAY.minusDays(9));

        DocumentContext json = 학원_대시보드(academyId, 7);

        assertThat(json.<List<String>>read("$.data.recent_events[*].kind")).containsExactly("run_confirmed");
        assertThat(((Number) json.read("$.data.recent_events[0].pending_change_count")).longValue()).isEqualTo(2);
        assertThat(json.<Object>read("$.data.recent_events[0].delay_minutes")).isNull();
        assertThat(oldRun).isPositive();
    }
}
