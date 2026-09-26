package src.backend.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.math.BigDecimal;
import java.time.Clock;
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
import org.springframework.context.annotation.Import;

import com.jayway.jsonpath.JsonPath;

import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.exception.controller.EmergencyFixtures;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.run.repository.RunRepository;
import testsupport.clock.FixedClock20300401Config;

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
@Import(FixedClock20300401Config.class)
class AdminEmergencyControllerTest {

    private static final String RAISE = "/api/v1/runs/%d/emergency";

    private static final String LIST = "/api/v1/admin/emergencies";

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

        // R6 목표 3 — 기본값은 open 이라 acked 신고를 보려면 명시해야 한다(필드 계산 검증이 목적).
        String body = 목록을_조회한다(adminAccountId, "acked");

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

        // R6 목표 3 — 기본값은 open 이라 canceled 신고를 보려면 명시해야 한다(필드 계산 검증이 목적).
        String body = 목록을_조회한다(adminAccountId, "canceled");

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

        mockMvc.perform(post(ACK.formatted(emergencyId))
                        .header("Authorization", 메인관리자_토큰(adminAccountId)))
                .andExpect(status().isOk());

        // R6 목표 3 — ack 로 상태가 acked 가 됐으니 기본값(open)으로는 빠진다.
        String body = 목록을_조회한다(adminAccountId, "acked");

        Map<String, Object> item = 항목(body, emergencyId);
        assertThat(item).as("§6.11 은 §5.16 상속 7키를 다시 담아야 한다(Phase 13 목표 13 완료 기준 3)")
                .containsKeys("emergency_id", "raised_by", "position", "direction", "contacts", "raised_at",
                        "acked_by");
        assertThat(item).as("§6.11 도 §5.16 과 같은 키를 쓴다 — id 가 아니라 emergency_id")
                .doesNotContainKey("id")
                .containsKey("emergency_id");
        assertThat(item).doesNotContainKey("occurred_at");
        // BE-R1 목표 3 — §5.16·§6.11 표는 type 값을 소문자 스네이크로 적었으나(accident 등), 정정
        // 전에는 EmergencyType enum 의 name() 을 그대로 실어 대문자(ACCIDENT)로 나갔다.
        assertThat(item.get("type")).as("type 은 정본대로 소문자여야 한다").isEqualTo("accident");

        Map<String, Object> academyInfo = (Map<String, Object>) item.get("academy");
        assertThat(academyInfo).as("academy 는 §6.11 고유 필드다").isNotNull();
        assertThat(Long.parseLong((String) academyInfo.get("id"))).isEqualTo(academyId);

        Map<String, Object> raisedBy = (Map<String, Object>) item.get("raised_by");
        assertThat(raisedBy).containsEntry("name", "기사").containsEntry("role", "driver");
        assertThat(raisedBy.get("phone")).isNotNull();

        List<Map<String, Object>> contacts = (List<Map<String, Object>>) item.get("contacts");
        assertThat(contacts).extracting(c -> c.get("role")).containsExactlyInAnyOrder("driver", "escort");

        Map<String, Object> position = (Map<String, Object>) item.get("position");
        assertThat(new BigDecimal(position.get("lat").toString())).isEqualByComparingTo("37.500000");
        assertThat(position.get("recorded_at")).isNotNull();

        assertThat(item.get("direction")).as("direction 은 회차의 실제 방향값이어야 한다(수정 라운드 1, R3 Plant #1 연장)")
                .isEqualTo("to_academy");
        assertThat(item.get("raised_at")).as("raised_at = received_at").isNotNull();

        Map<String, Object> ackedBy = (Map<String, Object>) item.get("acked_by");
        assertThat(ackedBy).as("acked_by 는 확인한 관계자의 이름을 담아야 한다(수정 라운드 1, R3 Plant #5)")
                .containsEntry("name", "메인관리자");
    }

    /**
     * direction 이 상수 고정이 아니라 회차의 실제 값을 반영하는지 본다(목표 5, §6.11 쪽) —
     * {@code StaffEmergencyControllerTest} 의 같은 이름 시험과 근거가 같다.
     */
    @Test
    void direction_은_from_academy_회차에서도_실제_값을_반영한다() throws Exception {
        EmergencyFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, now(), Direction.FROM_ACADEMY);
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        long adminAccountId = fixtures.systemAdminAccount("메인관리자");

        long emergencyId = 신고를_발신한다(runId, driverAccountId, academyId);

        String body = 목록을_조회한다(adminAccountId);
        Map<String, Object> item = 항목(body, emergencyId);

        assertThat(item.get("direction")).as("direction 은 from_academy 회차에서 from_academy 를 돌려줘야 한다")
                .isEqualTo("from_academy");
    }

    // ── R6 목표 3·4 — status·academy_id 쿼리 필터 ────────────────────────

    @Test
    void status_필터를_생략하면_기본값_open만_담고_acked_canceled는_뺀다() throws Exception {
        EmergencyFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long adminAccountId = fixtures.systemAdminAccount("메인관리자");

        long openId = 신고를_발신한다(academyId, fixtures);
        long ackedId = 신고를_발신한다(academyId, fixtures);
        확인시각을_옮긴다(ackedId, now());
        long canceledId = 신고를_발신한다(academyId, fixtures);
        취소시각을_옮긴다(canceledId, now());

        String body = 목록을_조회한다(adminAccountId);

        assertThat(id목록(body)).as("기본값은 open 이라 acked·canceled 는 빠져야 한다")
                .contains(openId)
                .doesNotContain(ackedId, canceledId);
    }

    @Test
    void status_acked_를_주면_확인된_신고만_담는다() throws Exception {
        EmergencyFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long adminAccountId = fixtures.systemAdminAccount("메인관리자");

        long openId = 신고를_발신한다(academyId, fixtures);
        long ackedId = 신고를_발신한다(academyId, fixtures);
        확인시각을_옮긴다(ackedId, now());

        String body = mockMvc.perform(get(LIST).param("status", "acked")
                        .header("Authorization", 메인관리자_토큰(adminAccountId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(id목록(body)).contains(ackedId).doesNotContain(openId);
    }

    /** 미확인 배지는 status 필터와 무관하다(§6.11 → §5.16 · BR-066) — "확인됨" 을 열어도 열린 신고가 세어진다. */
    @Test
    void 미확인_배지는_status_필터를_따라가지_않는다() throws Exception {
        EmergencyFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long adminAccountId = fixtures.systemAdminAccount("메인관리자");

        신고를_발신한다(academyId, fixtures);
        long ackedId = 신고를_발신한다(academyId, fixtures);
        확인시각을_옮긴다(ackedId, now());

        String openBody = 목록을_조회한다(adminAccountId);
        String ackedBody = mockMvc.perform(get(LIST).param("status", "acked")
                        .header("Authorization", 메인관리자_토큰(adminAccountId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        long openBadge = ((Number) com.jayway.jsonpath.JsonPath.read(openBody, "$.data.unacked_count")).longValue();
        assertThat(openBadge).as("열린 신고가 있다").isPositive();
        assertThat(((Number) com.jayway.jsonpath.JsonPath.read(ackedBody, "$.data.unacked_count")).longValue())
                .as("탭을 바꿔도 배지는 같아야 한다").isEqualTo(openBadge);
    }

    @Test
    void 잘못된_status_값은_422_VALIDATION_FAILED_이다() throws Exception {
        long adminAccountId = fixtures().systemAdminAccount("메인관리자");

        mockMvc.perform(get(LIST).param("status", "bogus")
                        .header("Authorization", 메인관리자_토큰(adminAccountId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void academy_id_를_주면_그_학원_신고만_담는다() throws Exception {
        EmergencyFixtures fixtures = fixtures();
        long academyA = fixtures.academy();
        long academyB = fixtures.academy();
        long adminAccountId = fixtures.systemAdminAccount("메인관리자");

        long alertInA = 신고를_발신한다(academyA, fixtures);
        long alertInB = 신고를_발신한다(academyB, fixtures);

        String body = mockMvc.perform(get(LIST).param("academy_id", String.valueOf(academyA))
                        .header("Authorization", 메인관리자_토큰(adminAccountId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(id목록(body)).contains(alertInA).doesNotContain(alertInB);
    }

    /**
     * 학원 하나에 비상 신고 여러 건을 만들 때 쓴다 — 버스를 매번 새로 만든다. 같은 버스·같은
     * departTime 으로 회차를 두 번 만들면 스케줄 UNIQUE 제약(같은 버스가 같은 시각에 중복 배차되지
     * 않게 하는 제약)에 걸린다.
     */
    private long 신고를_발신한다(long academyId, EmergencyFixtures fixtures) throws Exception {
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, now());
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        return 신고를_발신한다(runId, driverAccountId, academyId);
    }

    private List<Long> id목록(String body) {
        List<String> ids = JsonPath.read(body, "$.data.items[*].emergency_id");
        return ids.stream().map(Long::parseLong).toList();
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
        String emergencyId = JsonPath.read(body, "$.data.emergency_id");
        return Long.parseLong(emergencyId);
    }

    private String 목록을_조회한다(long adminAccountId) throws Exception {
        return mockMvc.perform(get(LIST).header("Authorization", 메인관리자_토큰(adminAccountId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /**
     * R6 목표 3 필터 도입 이후 — 기존 시험들이 acked·canceled 신고를 만들고 나서 status 를 주지
     * 않고 조회했다. 필터가 없던 시절에는 문제가 없었지만, 이제 기본값이 open 이라 그 신고들이
     * 응답에서 빠진다. 그 시험들은 필터링 자체가 아니라 필드 계산을 검증하는 것이므로, 만든
     * 신고의 실제 상태에 맞는 status 를 명시해 원래 검증 대상을 유지한다.
     */
    private String 목록을_조회한다(long adminAccountId, String status) throws Exception {
        return mockMvc.perform(get(LIST).param("status", status)
                        .header("Authorization", 메인관리자_토큰(adminAccountId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** emergency_id 로 걸러 항목 1건을 읽는다 — 시드 행이 섞여 있어도 흔들리지 않는다. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> 항목(String body, long emergencyId) {
        List<Map<String, Object>> items = JsonPath.read(body,
                "$.data.items[?(@.emergency_id == %d)]".formatted(emergencyId));
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
                "$.data.items[?(@.emergency_id == %d)].elapsed_since_raised".formatted(emergencyId));
        assertThat(values).as("emergency_id=%d 행이 응답에 없다".formatted(emergencyId)).hasSize(1);
        return values.get(0).longValue();
    }

    private boolean 확인여부(String body, long emergencyId) {
        List<Boolean> values = JsonPath.read(body,
                "$.data.items[?(@.emergency_id == %d)].staff_acked".formatted(emergencyId));
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
