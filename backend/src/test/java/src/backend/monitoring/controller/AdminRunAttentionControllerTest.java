package src.backend.monitoring.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.exception.controller.EmergencyFixtures;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;

import testsupport.clock.FixedClock20300401Config;

/**
 * §6.15 {@code GET /admin/runs/attention} — 전체 관제의 학원별 오늘 지연·확정 실패 집계(R46-FUFEAT ④, Ruling 543).
 *
 * <p>고정 시계가 2030-04-01 이라 그날 회차가 "오늘" 이다. 시드 학원이 같은 날 회차를 가질 수 있어 학원마다 새로 만든
 * 학원 식별자로 걸러 읽는다 — 집계는 학원별이라 다른 학원의 값이 섞이지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(FixedClock20300401Config.class)
class AdminRunAttentionControllerTest {

    private static final String ATTENTION = "/api/v1/admin/runs/attention";

    private static final LocalDate TODAY = LocalDate.of(2030, 4, 1);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private BusRepository busRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ManagerRepository managerRepository;

    @Autowired
    private AssignmentRepository assignmentRepository;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private AcademyStaffRepository academyStaffRepository;

    private EmergencyFixtures fixtures() {
        return new EmergencyFixtures(academyRepository, busRepository, accountRepository, managerRepository,
                assignmentRepository, runRepository, academyStaffRepository);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 오늘_회차의_지연과_확정_실패를_학원별로_센다() throws Exception {
        EmergencyFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long senderId = fixtures.staffAccount(academyId, "발신자");
        long adminId = fixtures.systemAdminAccount("메인관리자");

        long delayedMoving = 회차(fixtures, academyId, TODAY, 0, "moving", false);
        지연_알림(delayedMoving, senderId);
        long delayedConfirmed = 회차(fixtures, academyId, TODAY, 0, "confirmed", false);
        지연_알림(delayedConfirmed, senderId);
        지연_알림(delayedConfirmed, senderId); // 같은 회차의 두 번째 알림은 회차를 두 번 세지 않는다
        회차(fixtures, academyId, TODAY, 3, "idle", false); // 확정 실패
        회차(fixtures, academyId, TODAY, 1, "idle", false); // 확정 실패

        // 세지 않는 것들
        지연_알림(회차(fixtures, academyId, TODAY, 0, "finished", false), senderId); // 끝난 회차의 지연
        회차(fixtures, academyId, TODAY, 5, "idle", true); // 임시 취소된 회차의 실패
        지연_알림(회차(fixtures, academyId, TODAY, 0, "moving", true), senderId); // 임시 취소된 회차의 지연
        회차(fixtures, academyId, TODAY, 0, "idle", false); // 실패 0 — 확정 대기일 뿐
        회차(fixtures, academyId, TODAY, 4, "confirmed", false); // 이미 확정됨 — 옛 실패 횟수는 읽지 않는다
        회차(fixtures, academyId, TODAY.minusDays(1), 6, "idle", false); // 어제 회차
        지연_알림(회차(fixtures, academyId, TODAY.minusDays(1), 0, "moving", false), senderId);

        String body = mockMvc.perform(get(ATTENTION).header("Authorization", 메인관리자_토큰(adminId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Map<String, Object>> mine = JsonPath.read(body,
                "$.data.items[?(@.academy_id == %d)]".formatted(academyId));
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0)).containsEntry("delayed_runs", 2).containsEntry("confirm_failed_runs", 2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 지연만_있는_학원과_확정_실패만_있는_학원도_각각_목록에_있다() throws Exception {
        EmergencyFixtures fixtures = fixtures();
        long delayOnly = fixtures.academy();
        long failureOnly = fixtures.academy();
        long senderId = fixtures.staffAccount(delayOnly, "발신자");
        long adminId = fixtures.systemAdminAccount("메인관리자");
        지연_알림(회차(fixtures, delayOnly, TODAY, 0, "moving", false), senderId);
        회차(fixtures, failureOnly, TODAY, 2, "idle", false);

        String body = mockMvc.perform(get(ATTENTION).header("Authorization", 메인관리자_토큰(adminId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Map<String, Object>> delayRow = JsonPath.read(body, "$.data.items[?(@.academy_id == %d)]".formatted(delayOnly));
        List<Map<String, Object>> failureRow = JsonPath.read(body, "$.data.items[?(@.academy_id == %d)]".formatted(failureOnly));
        assertThat(delayRow).singleElement().satisfies(row ->
                assertThat(row).containsEntry("delayed_runs", 1).containsEntry("confirm_failed_runs", 0));
        assertThat(failureRow).singleElement().satisfies(row ->
                assertThat(row).containsEntry("delayed_runs", 0).containsEntry("confirm_failed_runs", 1));
    }

    @Test
    void 문제가_없는_학원은_목록에_없다() throws Exception {
        EmergencyFixtures fixtures = fixtures();
        long quietAcademy = fixtures.academy();
        회차(fixtures, quietAcademy, TODAY, 0, "idle", false);
        long adminId = fixtures.systemAdminAccount("메인관리자");

        mockMvc.perform(get(ATTENTION).header("Authorization", 메인관리자_토큰(adminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.academy_id == %d)]".formatted(quietAcademy)).isEmpty());
    }

    /**
     * §6.15 {@code today[]}(Ruling 805) — {@code items[]} 와 달리 문제 없는 학원·비활성 학원도 싣고, 회차 상태별 수(취소 제외)와
     * 같은 정의의 지연·확정 실패 수를 학원 식별자 오름차순으로 준다.
     */
    @Test
    @SuppressWarnings("unchecked")
    void today_는_문제_없는_학원과_비활성_학원까지_상태별_회차_수와_함께_싣는다() throws Exception {
        EmergencyFixtures fixtures = fixtures();
        long busyAcademy = fixtures.academy();
        long quietAcademy = fixtures.academy();
        long emptyAcademy = fixtures.academy();
        long senderId = fixtures.staffAccount(busyAcademy, "발신자");
        long adminId = fixtures.systemAdminAccount("메인관리자");

        회차(fixtures, busyAcademy, TODAY, 0, "idle", false);
        회차(fixtures, busyAcademy, TODAY, 2, "idle", false); // 확정 실패
        회차(fixtures, busyAcademy, TODAY, 0, "confirmed", false);
        지연_알림(회차(fixtures, busyAcademy, TODAY, 0, "moving", false), senderId); // 지연
        회차(fixtures, busyAcademy, TODAY, 0, "moving", false);
        회차(fixtures, busyAcademy, TODAY, 0, "finished", false);
        회차(fixtures, busyAcademy, TODAY, 0, "idle", true); // 취소 — run_count 에도 by_status 에도 안 든다
        회차(fixtures, busyAcademy, TODAY.minusDays(1), 0, "moving", false); // 어제 — 오늘 요약이 아니다
        회차(fixtures, quietAcademy, TODAY, 0, "finished", false);
        academyRepository.findById(quietAcademy).orElseThrow().changeStatus(src.backend.academy.entity.AcademyStatus.INACTIVE);
        academyRepository.flush();

        String body = mockMvc.perform(get(ATTENTION).header("Authorization", 메인관리자_토큰(adminId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Map<String, Object>> busy = JsonPath.read(body, "$.data.today[?(@.academy_id == %d)]".formatted(busyAcademy));
        assertThat(busy).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("academy_status", "active").containsEntry("run_count", 6)
                    .containsEntry("delayed_runs", 1).containsEntry("confirm_failed_runs", 1);
            assertThat((Map<String, Object>) row.get("by_status")).containsEntry("idle", 2)
                    .containsEntry("confirmed", 1).containsEntry("moving", 2).containsEntry("finished", 1);
            assertThat(row.get("academy_name")).isNotNull();
        });
        List<Map<String, Object>> quiet = JsonPath.read(body, "$.data.today[?(@.academy_id == %d)]".formatted(quietAcademy));
        assertThat(quiet).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("academy_status", "inactive").containsEntry("run_count", 1)
                    .containsEntry("delayed_runs", 0).containsEntry("confirm_failed_runs", 0);
            assertThat((Map<String, Object>) row.get("by_status")).containsEntry("finished", 1)
                    .containsEntry("idle", 0).containsEntry("confirmed", 0).containsEntry("moving", 0);
        });
        List<Map<String, Object>> empty = JsonPath.read(body, "$.data.today[?(@.academy_id == %d)]".formatted(emptyAcademy));
        assertThat(empty).singleElement().satisfies(row -> assertThat(row).containsEntry("run_count", 0));

        // 학원 식별자 오름차순, 그리고 문제 없는 학원은 기존 items[] 에는 여전히 없다
        List<Object> ids = JsonPath.read(body, "$.data.today[*].academy_id");
        List<Long> asLongs = ids.stream().map(id -> Long.valueOf(id.toString())).toList();
        assertThat(asLongs).isSorted();
        assertThat(JsonPath.<List<Object>>read(body, "$.data.items[?(@.academy_id == %d)]".formatted(quietAcademy)))
                .isEmpty();
    }

    @Test
    void 메인_관리자가_아니면_403_이다() throws Exception {
        EmergencyFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long staffId = fixtures.staffAccount(academyId, "관계자");

        mockMvc.perform(get(ATTENTION).header("Authorization",
                        "Bearer " + tokenProvider.createAccessToken(staffId, academyId, Role.STAFF, AccountStatus.ACTIVE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    /** 차량마다 새로 만들어 유일성(차량·날짜·방향·출발 시각)을 피한다 — 상태·연속 실패·취소는 SQL 로 직접 맞춘다. */
    private long 회차(EmergencyFixtures fixtures, long academyId, LocalDate serviceDate, int consecutiveFailures,
            String status, boolean canceled) {
        long busId = fixtures.bus(academyId);
        OffsetDateTime departTime = serviceDate.atTime(9, 0).atOffset(java.time.ZoneOffset.ofHours(9));
        long runId = runRepository.save(Run.forSchedule(academyId, busId, null, serviceDate, Direction.TO_ACADEMY,
                departTime, departTime.minusMinutes(30), "출발지", "도착지", null)).getId();
        jdbcTemplate.update("UPDATE run SET status = ?, consecutive_failures = ? WHERE id = ?", status,
                consecutiveFailures, runId);
        if (canceled) {
            jdbcTemplate.update("UPDATE run SET canceled_at = now(), cancel_source = 'staff' WHERE id = ?", runId);
        }
        return runId;
    }

    private void 지연_알림(long runId, long senderAccountId) {
        jdbcTemplate.update("""
                INSERT INTO delay_notice (run_id, sent_by_account_id, minutes, reason)
                VALUES (?, ?, 10, 'traffic')
                """, runId, senderAccountId);
    }

    private String 메인관리자_토큰(long accountId) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, null, Role.SYSTEM_ADMIN, AccountStatus.ACTIVE);
    }
}
