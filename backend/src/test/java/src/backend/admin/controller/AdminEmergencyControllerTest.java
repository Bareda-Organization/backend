package src.backend.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

import com.jayway.jsonpath.JsonPath;

import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.exception.controller.EmergencyFixtures;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.run.repository.RunRepository;

/**
 * 메인 관리자 콘솔의 전 학원 비상 알림 조회(EXC-04, Phase 11 T2 목표 11) — {@code GET /admin/emergencies}.
 * 게이트 리뷰(R2)가 지목한 대로 이 컨트롤러를 태우는 시험이 이전 라운드 diff 전체에서 0건이었다 —
 * 그래서 응답 필드명이 정본(API_SPEC.md:1923, {@code elapsed_since_raised})과 어긋난 채
 * ({@code elapsed_seconds_since_raised}) 아무 데도 걸리지 않았다.
 *
 * <p>이 조회는 학원으로 좁히지 않는다({@code @AcademyScopeExempt}) — 로컬 시드(V2)가 이미
 * {@code emergency_alert} 행 1건을 심어 두므로, 응답 목록 크기·순서에 기대지 않고 <b>내가 만든
 * 행을 id 로 걸러</b> 검증한다.
 *
 * <p>{@code RedisTestContainerBase} 를 상속하지 않는다 — {@code StaffEmergencyControllerTest} 와 같은
 * 이유로, 발신 자체는 위치 캐시가 비어도(목표 8) 성공하므로 이 시험(목표 11 전용)에는 실제 위치
 * 캐싱 검증이 필요 없다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminEmergencyControllerTest {

    private static final String RAISE = "/api/v1/runs/%d/emergency";

    private static final String LIST = "/api/v1/admin/emergencies";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private Clock clock;

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

    @TestConfiguration
    static class FixedClockConfig {

        private static final Instant FIXED = Instant.parse("2030-04-01T03:00:00Z"); // 2030-04-01 12:00 KST

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED, ZoneId.of("Asia/Seoul"));
        }
    }

    private EmergencyFixtures fixtures() {
        return new EmergencyFixtures(academyRepository, busRepository, accountRepository, managerRepository,
                assignmentRepository, runRepository, academyStaffRepository);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    // ── goal 11 — 필드명 · 경과시간(elapsed_since_raised) 계산 ──────────────

    @Test
    void 미확인_신고는_staff_acked가_false이고_경과시간이_now까지_계산된다() throws Exception {
        long emergencyId = 신고를_발신한다_기본();
        접수시각을_옮긴다(emergencyId, now().minusSeconds(120));
        long adminAccountId = fixtures().systemAdminAccount("메인관리자");

        String body = 목록을_조회한다(adminAccountId);

        assertThat(경과시간(body, emergencyId)).as("정본 키는 elapsed_since_raised 다(API_SPEC.md:1923)")
                .isEqualTo(120L);
        assertThat(확인여부(body, emergencyId)).isFalse();
    }

    @Test
    void 확인된_신고는_경과시간이_확인_시각에서_멈춘다() throws Exception {
        long emergencyId = 신고를_발신한다_기본();
        접수시각을_옮긴다(emergencyId, now().minusSeconds(300));
        확인시각을_옮긴다(emergencyId, now().minusSeconds(100));
        long adminAccountId = fixtures().systemAdminAccount("메인관리자");

        String body = 목록을_조회한다(adminAccountId);

        assertThat(확인여부(body, emergencyId)).isTrue();
        assertThat(경과시간(body, emergencyId))
                .as("now 까지 계속 흘렀다면 300 이 나온다 — ackedAt(경과 200초 시점)에서 멈춰야 한다")
                .isEqualTo(200L);
    }

    @Test
    void 취소된_신고는_경과시간이_취소_시각에서_멈춘다() throws Exception {
        long emergencyId = 신고를_발신한다_기본();
        접수시각을_옮긴다(emergencyId, now().minusSeconds(500));
        취소시각을_옮긴다(emergencyId, now().minusSeconds(200));
        long adminAccountId = fixtures().systemAdminAccount("메인관리자");

        String body = 목록을_조회한다(adminAccountId);

        assertThat(경과시간(body, emergencyId))
                .as("now 까지 계속 흘렀다면 500 이 나온다 — canceledAt(경과 300초 시점)에서 멈춰야 한다")
                .isEqualTo(300L);
    }

    @Test
    void 학원_관계자는_메인관리자_콘솔을_호출할_수_없다() throws Exception {
        EmergencyFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long staffAccountId = fixtures.staffAccount(academyId, "직원");

        mockMvc.perform(get(LIST).header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isForbidden());
    }

    // ── Phase 13 목표 13 — §6.11 은 §5.16 상속 필드를 독립으로 다시 검증한다 ─────
    //
    // 학원 관계자 화면(§5.16)이 통과해도 메인관리자 콘솔(§6.11)이 같은 필드를 담는다는 보장은
    // 되지 않는다 — 두 응답 DTO(EmergencyStaffItemResponse·AdminEmergencyItemResponse)가 서로
    // 다른 record 라 한쪽만 고치고 한쪽을 빠뜨릴 수 있다(Phase 13 목표 13 goal-table §7 요구,
    // T2 P13 리뷰의 "§6.11 응답에서 §5.16 상속 필드 하나를 빼면 이 시험만 실패해야 한다" 음성
    // 대조 대상).

    @Test
    void 목록_응답은_5_16_상속_필드를_academy_own_필드와_함께_담는다() throws Exception {
        EmergencyFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, now());
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        fixtures.assignedManager(academyId, runId, ManagerRole.ESCORT, "동승자", now());
        long adminAccountId = fixtures.systemAdminAccount("메인관리자");

        long emergencyId = 신고를_발신한다(runId, driverAccountId, academyId);
        위치를_기록한다(emergencyId, new BigDecimal("37.500000"), new BigDecimal("127.000000"), now().minusSeconds(30));

        String body = 목록을_조회한다(adminAccountId);

        Map<String, Object> item = 항목(body, emergencyId);
        assertThat(item).as("§6.11 도 §5.16 과 같은 키를 쓴다 — id 가 아니라 emergency_id")
                .doesNotContainKey("id")
                .containsKey("emergency_id");
        assertThat(item).doesNotContainKey("occurred_at");

        Map<String, Object> academyInfo = (Map<String, Object>) item.get("academy");
        assertThat(academyInfo).as("academy 는 §6.11 고유 필드다").isNotNull();
        assertThat(((Number) academyInfo.get("id")).longValue()).isEqualTo(academyId);

        Map<String, Object> raisedBy = (Map<String, Object>) item.get("raised_by");
        assertThat(raisedBy).containsEntry("name", "기사").containsEntry("role", "driver");
        assertThat(raisedBy.get("phone")).isNotNull();

        List<Map<String, Object>> contacts = (List<Map<String, Object>>) item.get("contacts");
        assertThat(contacts).extracting(c -> c.get("role")).containsExactlyInAnyOrder("driver", "escort");

        Map<String, Object> position = (Map<String, Object>) item.get("position");
        assertThat(new BigDecimal(position.get("lat").toString())).isEqualByComparingTo("37.500000");
        assertThat(position.get("recorded_at")).isNotNull();

        assertThat(item.get("direction")).isNotNull();
        assertThat(item.get("raised_at")).as("raised_at = received_at").isNotNull();
    }

    // ── 픽스처 · 호출 도우미 ──────────────────────────────────────────────

    private long 신고를_발신한다_기본() throws Exception {
        EmergencyFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, now());
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        return 신고를_발신한다(runId, driverAccountId, academyId);
    }

    private long 신고를_발신한다(long runId, long accountId, long academyId) throws Exception {
        String body = mockMvc.perform(post(RAISE.formatted(runId))
                        .header("Authorization", 토큰(accountId, academyId, Role.DRIVER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"accident\",\"memo\":null,\"client_key\":\"%s\"}"
                                .formatted(UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Number emergencyId = JsonPath.read(body, "$.data.emergency_id");
        return emergencyId.longValue();
    }

    private String 목록을_조회한다(long adminAccountId) throws Exception {
        return mockMvc.perform(get(LIST).header("Authorization", 메인관리자_토큰(adminAccountId)))
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

    /** 위치 캐시가 있었다면 붙었을 값을 시험용으로 직접 심는다({@code RunPositionCache} 우회, Ruling 236). */
    private void 위치를_기록한다(long emergencyId, BigDecimal lat, BigDecimal lng, OffsetDateTime recordedAt) {
        jdbcTemplate.update("UPDATE emergency_alert SET lat = ?, lng = ?, position_recorded_at = ? WHERE id = ?",
                lat, lng, recordedAt, emergencyId);
        entityManager.clear();
    }

    /**
     * emergency_id 로 걸러 elapsed_since_raised 값을 읽는다 — 시드 행이 섞여 있어도 흔들리지
     * 않는다. 키는 {@code id} 가 아니라 {@code emergency_id} 다(Phase 13 목표 13 판정 ①, §5.16 상속).
     */
    private Long 경과시간(String body, long emergencyId) {
        List<Number> values = JsonPath.read(body,
                "$.data.emergencies[?(@.emergency_id == %d)].elapsed_since_raised".formatted(emergencyId));
        assertThat(values).as("emergency_id=%d 행이 응답에 없다".formatted(emergencyId)).hasSize(1);
        return values.get(0).longValue();
    }

    private boolean 확인여부(String body, long emergencyId) {
        List<Boolean> values = JsonPath.read(body,
                "$.data.emergencies[?(@.emergency_id == %d)].staff_acked".formatted(emergencyId));
        assertThat(values).hasSize(1);
        return values.get(0);
    }

    /** {@code EmergencyControllerTest#접수시각을_옮긴다} 와 같은 이유(1차 캐시 우회)로 clear() 를 함께 한다. */
    private void 접수시각을_옮긴다(long emergencyId, OffsetDateTime receivedAt) {
        jdbcTemplate.update("UPDATE emergency_alert SET received_at = ? WHERE id = ?", receivedAt, emergencyId);
        entityManager.clear();
    }

    private void 확인시각을_옮긴다(long emergencyId, OffsetDateTime ackedAt) {
        jdbcTemplate.update("UPDATE emergency_alert SET acked_at = ? WHERE id = ?", ackedAt, emergencyId);
        entityManager.clear();
    }

    private void 취소시각을_옮긴다(long emergencyId, OffsetDateTime canceledAt) {
        jdbcTemplate.update("UPDATE emergency_alert SET canceled_at = ? WHERE id = ?", canceledAt, emergencyId);
        entityManager.clear();
    }

    private String 토큰(long accountId, long academyId, Role role) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, role, AccountStatus.ACTIVE);
    }

    private String 메인관리자_토큰(long accountId) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, null, Role.SYSTEM_ADMIN, AccountStatus.ACTIVE);
    }
}
