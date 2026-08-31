package src.backend.exception.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.run.repository.RunRepository;

/**
 * 학원 관계자·메인관리자 단말의 비상 알림 수신 범위·확인(ack) API(EXC-04, Phase 11 T2 목표 6·7·10) —
 * {@code /staff/emergencies/{id}/ack}.
 *
 * <p>목표 6·7 은 컨트롤러가 아니라 신고 접수 시점에 {@code EmergencyNotificationListener} 가 채우는
 * {@code notification_log} 를 직접 SQL 로 대조한다 — 그 리스너가 목표 6·7 이 실제로 등재되는
 * 유일한 지점이라({@code exception/controller} 밖의 소유 경로 산출물) API 응답만으로는 "누가 못
 * 받았는가" 를 검증할 수 없다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StaffEmergencyControllerTest {

    private static final String RAISE = "/api/v1/runs/%d/emergency";

    private static final String ACK = "/api/v1/staff/emergencies/%d/ack";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

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

    // ── goal 6 — 학원 관계자 + 메인관리자 동시 수신, 목표 7 — 학부모·학생 미수신 ──────────

    @Test
    void 비상_신고는_학원_관계자와_메인관리자_각각에게_한_건씩_알림을_남긴다() throws Exception {
        EmergencyFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, OffsetDateTime.now());
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사",
                OffsetDateTime.now());
        long staffAccountId = fixtures.staffAccount(academyId, "직원");
        long adminAccountId = fixtures.systemAdminAccount("메인관리자");

        long emergencyId = 신고를_발신한다(runId, driverAccountId, academyId);

        assertThat(알림_행수(emergencyId, staffAccountId, "staff")).as("학원 관계자는 목표 6 에 따라 항상 받아야 한다")
                .isEqualTo(1);
        assertThat(알림_행수(emergencyId, adminAccountId, "system_admin")).as("메인관리자도 목표 6 에 따라 항상 받아야 한다")
                .isEqualTo(1);
    }

    @Test
    void 학부모와_학생은_비상_신고_알림을_받지_않는다() throws Exception {
        EmergencyFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, OffsetDateTime.now());
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사",
                OffsetDateTime.now());
        long parentAccountId = fixtures.parentAccount(academyId, "학부모");
        long studentAccountId = fixtures.studentAccount(academyId, "학생");

        신고를_발신한다(runId, driverAccountId, academyId);

        assertThat(전체_수신자_행수(parentAccountId)).as("목표 7 — 학부모는 비상 알림을 받으면 안 된다").isZero();
        assertThat(전체_수신자_행수(studentAccountId)).as("목표 7 — 학생은 비상 알림을 받으면 안 된다").isZero();
    }

    // ── goal 10 — 확인(ack) 처리 · 이력 반영 · 중복 확인 거절 ────────────────

    @Test
    void 학원_관계자의_확인은_발신자에게_반영되고_이력에_남는다() throws Exception {
        EmergencyFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, OffsetDateTime.now());
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사",
                OffsetDateTime.now());
        long staffAccountId = fixtures.staffAccount(academyId, "직원");
        long emergencyId = 신고를_발신한다(runId, driverAccountId, academyId);

        mockMvc.perform(post(ACK.formatted(emergencyId))
                        .header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.acked_at").exists());

        assertThat(확인자(emergencyId)).isEqualTo(staffAccountId);
        assertThat(확인시각(emergencyId)).as("이력은 삭제되지 않고 acked_at 으로 남아야 한다").isNotNull();
    }

    @Test
    void 메인관리자도_같은_경로로_확인할_수_있다() throws Exception {
        EmergencyFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, OffsetDateTime.now());
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사",
                OffsetDateTime.now());
        long adminAccountId = fixtures.systemAdminAccount("메인관리자");
        long emergencyId = 신고를_발신한다(runId, driverAccountId, academyId);

        mockMvc.perform(post(ACK.formatted(emergencyId))
                        .header("Authorization", 메인관리자_토큰(adminAccountId)))
                .andExpect(status().isOk());

        assertThat(확인자(emergencyId)).isEqualTo(adminAccountId);
    }

    @Test
    void 이미_확인된_신고는_다시_확인할_수_없다() throws Exception {
        EmergencyFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, OffsetDateTime.now());
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사",
                OffsetDateTime.now());
        // 학원당 활성 관계자는 1명 정원(uk_academy_staff_academy_active, Ruling 139)이라 같은 학원에
        // 직원을 2명 만들 수 없다 — 두 번째 확인 시도자는 메인관리자로 둔다(교차 역할로도 거절되는지 함께 본다).
        long staffAccountId = fixtures.staffAccount(academyId, "직원");
        long adminAccountId = fixtures.systemAdminAccount("메인관리자");
        long emergencyId = 신고를_발신한다(runId, driverAccountId, academyId);

        mockMvc.perform(post(ACK.formatted(emergencyId))
                        .header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk());

        mockMvc.perform(post(ACK.formatted(emergencyId))
                        .header("Authorization", 메인관리자_토큰(adminAccountId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ALREADY_ACKED"));

        assertThat(확인자(emergencyId)).as("먼저 확인한 사람의 기록이 덮어써지면 안 된다").isEqualTo(staffAccountId);
    }

    // ── 픽스처 · 호출 도우미 ──────────────────────────────────────────────

    private String 토큰(long accountId, long academyId, Role role) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, role, AccountStatus.ACTIVE);
    }

    private String 메인관리자_토큰(long accountId) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, null, Role.SYSTEM_ADMIN, AccountStatus.ACTIVE);
    }

    private long 신고를_발신한다(long runId, long accountId, long academyId) throws Exception {
        String body = mockMvc.perform(post(RAISE.formatted(runId))
                        .header("Authorization", 토큰(accountId, academyId, Role.DRIVER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"accident\",\"memo\":null,\"client_key\":\"%s\"}"
                                .formatted(UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Number emergencyId = com.jayway.jsonpath.JsonPath.read(body, "$.data.emergency_id");
        return emergencyId.longValue();
    }

    private int 알림_행수(long emergencyId, long recipientAccountId, String recipientRole) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_log WHERE recipient_account_id = ? AND recipient_role = ? "
                        + "AND type = 'emergency' AND dedup_key = ?",
                Integer.class, recipientAccountId, recipientRole, "emergency:%d:%d".formatted(emergencyId,
                        recipientAccountId));
        return count == null ? 0 : count;
    }

    private int 전체_수신자_행수(long recipientAccountId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_log WHERE recipient_account_id = ?", Integer.class,
                recipientAccountId);
        return count == null ? 0 : count;
    }

    /**
     * ack() 은 더티 체킹에 맡기고 명시적으로 save 하지 않는다 — JPQL 파생 쿼리끼리는 Hibernate 가
     * 실행 전에 자동으로 플러시하지만, jdbcTemplate 의 순수 SQL 은 영속성 컨텍스트를 거치지 않아
     * 그 자동 플러시 대상이 아니다. 그래서 원시 SQL 로 읽기 전에 직접 flush 한다(최초 실행에서
     * "확인 직후 조회가 null" 로 걸린 함정).
     */
    private Long 확인자(long emergencyId) {
        entityManager.flush();
        return jdbcTemplate.queryForObject("SELECT acked_by FROM emergency_alert WHERE id = ?", Long.class,
                emergencyId);
    }

    private OffsetDateTime 확인시각(long emergencyId) {
        entityManager.flush();
        return jdbcTemplate.queryForObject("SELECT acked_at FROM emergency_alert WHERE id = ?",
                OffsetDateTime.class, emergencyId);
    }
}
