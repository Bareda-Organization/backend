package src.backend.exception.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

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

    private static final String LIST = "/api/v1/staff/emergencies";

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

    // ── Phase 13 목표 13 — §5.16 응답 필드 형태(정본 정합) ────────────────────

    @Test
    void 목록_응답은_정본이_요구하는_중첩_형태를_따른다() throws Exception {
        EmergencyFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, OffsetDateTime.now());
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사",
                OffsetDateTime.now());
        fixtures.assignedManager(academyId, runId, ManagerRole.ESCORT, "동승자", OffsetDateTime.now());
        long staffAccountId = fixtures.staffAccount(academyId, "직원");

        long emergencyId = 신고를_발신한다(runId, driverAccountId, academyId);
        OffsetDateTime recordedAt = OffsetDateTime.now().minusSeconds(30);
        위치를_기록한다(emergencyId, new BigDecimal("37.500000"), new BigDecimal("127.000000"), recordedAt);

        String body = 목록을_조회한다(staffAccountId, academyId);

        Map<String, Object> item = 항목(body, emergencyId);
        assertThat(item).as("정본 키는 id 가 아니라 emergency_id 다(Phase 13 목표 13 판정 ①)")
                .doesNotContainKey("id")
                .containsKey("emergency_id");
        assertThat(item).as("occurred_at 은 §5.16 응답에 없다(Phase 13 목표 13 판정 ①)")
                .doesNotContainKey("occurred_at");

        Map<String, Object> raisedBy = (Map<String, Object>) item.get("raised_by");
        assertThat(raisedBy).containsEntry("name", "기사").containsEntry("role", "driver");
        assertThat(raisedBy.get("phone")).isNotNull();

        List<Map<String, Object>> contacts = (List<Map<String, Object>>) item.get("contacts");
        assertThat(contacts).as("그 회차에 배치된 기사·동승자 전원이 담겨야 한다(Phase 13 목표 13 판정 ②)")
                .extracting(c -> c.get("role"))
                .containsExactlyInAnyOrder("driver", "escort");

        Map<String, Object> position = (Map<String, Object>) item.get("position");
        assertThat(new BigDecimal(position.get("lat").toString())).isEqualByComparingTo("37.500000");
        assertThat(position.get("recorded_at")).as("Ruling 236 — 위치 발신 장비가 찍은 시각").isNotNull();
    }

    @Test
    void 위치_캐시가_없으면_position_필드가_모두_null이다() throws Exception {
        // 이 시험 클래스는 RedisTestContainerBase 를 상속하지 않는다(클래스 자바독 참고) — 위치 캐시가
        // 항상 비어 있으므로 attachLocation 이 호출되지 않는 정상 경로를 그대로 검증한다.
        EmergencyFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, OffsetDateTime.now());
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사",
                OffsetDateTime.now());
        long staffAccountId = fixtures.staffAccount(academyId, "직원");
        long emergencyId = 신고를_발신한다(runId, driverAccountId, academyId);

        String body = 목록을_조회한다(staffAccountId, academyId);

        Map<String, Object> item = 항목(body, emergencyId);
        Map<String, Object> position = (Map<String, Object>) item.get("position");
        assertThat(position.get("lat")).isNull();
        assertThat(position.get("lng")).isNull();
        assertThat(position.get("recorded_at")).isNull();
    }

    @Test
    void raised_at은_occurred_at이_아니라_received_at을_쓴다() throws Exception {
        EmergencyFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, OffsetDateTime.now());
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사",
                OffsetDateTime.now());
        long staffAccountId = fixtures.staffAccount(academyId, "직원");
        long emergencyId = 신고를_발신한다(runId, driverAccountId, academyId);

        OffsetDateTime receivedAt = 접수시각(emergencyId);
        발신시각을_옮긴다(emergencyId, receivedAt.plusMinutes(10));

        String body = 목록을_조회한다(staffAccountId, academyId);

        Map<String, Object> item = 항목(body, emergencyId);
        OffsetDateTime raisedAt = OffsetDateTime.parse((String) item.get("raised_at"));
        assertThat(raisedAt).as("raised_at = received_at(occurred_at 은 조작 가능, Phase 13 목표 13 판정 ①)")
                .isEqualToIgnoringNanos(receivedAt);
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

    private String 목록을_조회한다(long staffAccountId, long academyId) throws Exception {
        return mockMvc.perform(get(LIST).header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** emergency_id 로 걸러 항목 1건을 읽는다 — 시드 행이 섞여 있어도 흔들리지 않는다. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> 항목(String body, long emergencyId) {
        List<Map<String, Object>> items = JsonPath.read(body,
                "$.data.emergencies[?(@.emergency_id == %d)]".formatted(emergencyId));
        assertThat(items).as("emergency_id=%d 행이 응답에 없다".formatted(emergencyId)).hasSize(1);
        return items.get(0);
    }

    /** 위치 캐시가 있었다면 붙었을 값을 시험용으로 직접 심는다({@link RunPositionCache} 우회, Ruling 236). */
    private void 위치를_기록한다(long emergencyId, BigDecimal lat, BigDecimal lng, OffsetDateTime recordedAt) {
        jdbcTemplate.update("UPDATE emergency_alert SET lat = ?, lng = ?, position_recorded_at = ? WHERE id = ?",
                lat, lng, recordedAt, emergencyId);
        entityManager.clear();
    }

    private void 발신시각을_옮긴다(long emergencyId, OffsetDateTime occurredAt) {
        jdbcTemplate.update("UPDATE emergency_alert SET occurred_at = ? WHERE id = ?", occurredAt, emergencyId);
        entityManager.clear();
    }

    private OffsetDateTime 접수시각(long emergencyId) {
        return jdbcTemplate.queryForObject("SELECT received_at FROM emergency_alert WHERE id = ?",
                OffsetDateTime.class, emergencyId);
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
