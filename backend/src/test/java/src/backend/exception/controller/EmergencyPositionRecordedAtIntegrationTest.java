package src.backend.exception.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
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
import testsupport.redis.RedisTestContainerBase;

/**
 * 비상 신고 발신 시점에 {@code RunPositionCache}(LOC-02, Ruling 208 계약)에 값이 있으면
 * {@code EmergencyAlert.attachLocation} 이 그 스냅샷의 {@code recordedAt}(위치 발신 장비가 찍은
 * 시각)을 {@code position_recorded_at} 컬럼에 그대로 저장하는지 검증한다(Phase 13 목표 13 판정 ③,
 * Ruling 236).
 *
 * <p>{@code StaffEmergencyControllerTest} 는 일부러 {@link RedisTestContainerBase} 를 상속하지
 * 않는다(그 클래스 자바독 참고) — 캐시가 비어도 신고 발신 자체는 성공해야 한다는 정상 경로를
 * 검증하기 위해서다({@code 위치_캐시가_없으면_position_필드가_모두_null이다}). 이 클래스는 그
 * 반대쪽, 즉 캐시가 <b>있을 때</b> 실제로 소비되는지를 검증하는 것이 유일한 목적이라 별도 파일로
 * 둔다 — 한 클래스에 Redis 컨테이너 상속 여부가 갈리는 시험을 섞으면 그 이유가 클래스 선언만
 * 보고는 드러나지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EmergencyPositionRecordedAtIntegrationTest extends RedisTestContainerBase {

    private static final String RAISE = "/api/v1/runs/%d/emergency";

    private static final String LIST = "/api/v1/staff/emergencies";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

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
    void 발신_시점에_위치_캐시가_있으면_recorded_at_은_캐시의_recordedAt_을_그대로_쓴다() throws Exception {
        EmergencyFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, OffsetDateTime.now());
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사",
                OffsetDateTime.now());
        long staffAccountId = fixtures.staffAccount(academyId, "직원");

        // recordedAt(장비가 찍은 시각)과 receivedAt(서버 수신 시각)을 다르게 둔다 — 둘 다 now() 로
        // 같으면 attachLocation 이 recordedAt 대신 receivedAt 이나 현재 시각을 잘못 저장해도
        // 이 시험이 못 잡는다.
        OffsetDateTime recordedAt = OffsetDateTime.now().minusMinutes(3).truncatedTo(ChronoUnit.SECONDS);
        OffsetDateTime receivedAt = OffsetDateTime.now().minusSeconds(5).truncatedTo(ChronoUnit.SECONDS);
        String json = """
                {"lat":37.512345,"lng":127.098765,"recordedAt":"%s","receivedAt":"%s","currentStopName":"임시 정류장"}"""
                .formatted(recordedAt, receivedAt);
        stringRedisTemplate.opsForValue().set("run:%d:position".formatted(runId), json);

        long emergencyId = 신고를_발신한다(runId, driverAccountId, academyId);

        String body = mockMvc.perform(get(LIST).header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Map<String, Object>> items = JsonPath.read(body,
                "$.data.emergencies[?(@.emergency_id == %d)]".formatted(emergencyId));
        assertThat(items).as("emergency_id=%d 행이 응답에 없다".formatted(emergencyId)).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> position = (Map<String, Object>) items.get(0).get("position");

        assertThat(new BigDecimal(position.get("lat").toString())).isEqualByComparingTo("37.512345");
        OffsetDateTime persisted = OffsetDateTime.parse((String) position.get("recorded_at"));
        // DB(timestamptz) 왕복 후에는 UTC(Z) 오프셋으로 돌아오므로 필드별 비교 대신 같은 순간인지로
        // 견준다 — isEqualToIgnoringNanos 는 오프셋이 다르면 시·분 필드가 갈려 오탐한다.
        assertThat(persisted.toInstant()).as("position.recorded_at 은 캐시의 recordedAt(장비 시각)이어야 한다 — receivedAt 이 아니다")
                .isEqualTo(recordedAt.toInstant());
    }

    private String 토큰(long accountId, long academyId, Role role) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, role, AccountStatus.ACTIVE);
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
}
