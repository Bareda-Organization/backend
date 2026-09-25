package src.backend.exception.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

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
 * 기사·동승자 단말의 비상 신고 발신·취소 API(EXC-04, Phase 11 T2 목표 5·8·9) — {@code /runs/{runId}/emergency}.
 *
 * <p>{@link RedisTestContainerBase} 를 상속한다 — 목표 8(위치 자동 첨부)이 실제 Redis 캐시(T1 계약,
 * {@code run:{runId}:position})를 읽는지 검증하려면 진짜 Redis 가 필요하다. 공유
 * {@code school-bus-redis-1} 대신 클래스 전용 컨테이너를 쓰는 이유는 그 베이스 클래스 자바독과 같다
 * (병렬 좌석 간 이름공간 충돌 회피).
 *
 * <p>목표 9 경계값(정확히 60초)은 Clock 을 흐르게 두는 대신 {@code received_at} 을 직접
 * UPDATE 해 고정 Clock 과의 차이를 원하는 만큼 정확히 만든다 — {@code DriverRunControllerTest} 의
 * ±10분 창 경계값 시험과 같은 형태이나, 그쪽은 창을 만드는 값(departTime)을 미리 계산해 넣는 반면
 * 이쪽은 창의 시작점(receivedAt)이 서버가 접수 시점에 스스로 채우는 값이라 사후에 SQL 로 되돌린다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EmergencyControllerTest extends RedisTestContainerBase {

    private static final String RAISE = "/api/v1/runs/%d/emergency";

    private static final String CANCEL = "/api/v1/runs/%d/emergency/%d";

    private static final String LIST = "/api/v1/runs/%d/emergencies";

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
    private StringRedisTemplate stringRedisTemplate;

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

    // ── goal 5 — 기사·동승자 둘 다 발신 가능 ──────────────────────────────

    @Test
    void 배치된_기사는_비상_신고를_발신할_수_있다() throws Exception {
        EmergencyFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, now());
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());

        mockMvc.perform(post(RAISE.formatted(runId))
                        .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(요청본문("accident", null, UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.emergency_id").exists())
                .andExpect(jsonPath("$.data.raised_at").exists());

        assertThat(신고건수(runId)).isEqualTo(1);
    }

    /**
     * 발신 응답의 {@code emergency_id}·{@code cancelable_until}·{@code notified}
     * (API_SPEC §4.14, BE-R1 목표 1) — 문자열 식별자·취소 창(+1분)·수신자 수(재직 관계자 + 메인관리자)를
     * 값으로 고정한다. 위 시험의 존재 확인({@code exists()})만으로는 타입(문자열 vs 숫자)과 실제 값이
     * 틀려도 통과하므로 별도로 둔다.
     *
     * <p>{@code notified} 의 메인관리자 몫은 {@link AccountRepository#countByRoleAndStatus} 가
     * 학원으로 좁히지 않고 전 플랫폼을 센다(§1.5, {@code Role#hasPlatformScope}) — 이 저장소는 local
     * 프로파일로 돌아 {@code db/migration-local/V2__seed_data.sql} 의 시드 메인관리자
     * ({@code sysadmin}, active)가 테스트 DB에 항상 이미 들어 있다. 그래서 기대값을 2 로 박지 않고
     * 이 시험이 만들기 전의 개수를 먼저 재서 기준으로 삼는다 — 그래야 시드 데이터 유무와 무관하게
     * "내가 만든 만큼 늘었는가" 만 검증한다.
     */
    @Test
    void 발신_응답은_문자열_식별자와_취소창과_수신자_수를_담는다() throws Exception {
        EmergencyFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, now());
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        long baselineActiveSystemAdmins = accountRepository.countByRoleAndStatus(Role.SYSTEM_ADMIN,
                AccountStatus.ACTIVE);
        fixtures.staffAccount(academyId, "직원1");
        fixtures.systemAdminAccount("관리자1");
        // jsonPath().value() 는 파싱된 JSON 값과 타입까지 맞아야 같다고 본다(net.minidev.json 은
        // int 범위 숫자를 Integer 로 판다) — long 을 그대로 넘기면 Long.equals(Integer) 가 항상
        // false 라 값이 같아도 실패한다. int 로 좁혀 비교한다.
        int expectedNotified = (int) (1 + baselineActiveSystemAdmins + 1);

        String body = mockMvc.perform(post(RAISE.formatted(runId))
                        .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(요청본문("accident", null, UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.emergency_id").isString())
                .andExpect(jsonPath("$.data.notified").value(expectedNotified))
                .andReturn().getResponse().getContentAsString();

        // 직렬화 문자열을 그대로 비교하면 오프셋 표기 차이(초 생략 여부)에 걸린다 — OffsetDateTime 으로
        // 파싱해 값으로 비교한다(§4.14 — cancelable_until 은 raised_at + 1분).
        var data = new ObjectMapper().readTree(body).path("data");
        OffsetDateTime raisedAt = OffsetDateTime.parse(data.path("raised_at").asText());
        OffsetDateTime cancelableUntil = OffsetDateTime.parse(data.path("cancelable_until").asText());
        assertThat(raisedAt).isEqualTo(now());
        assertThat(cancelableUntil).isEqualTo(now().plusMinutes(1));
    }

    @Test
    void 배치된_동승자도_비상_신고를_발신할_수_있다() throws Exception {
        EmergencyFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, now());
        long escortAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.ESCORT, "동승자", now());

        mockMvc.perform(post(RAISE.formatted(runId))
                        .header("Authorization", 토큰(escortAccountId, academyId, Role.ESCORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(요청본문("vehicle_fault", null, UUID.randomUUID())))
                .andExpect(status().isCreated());

        assertThat(신고건수(runId)).as("동승자 발신도 기사와 동일하게 성립해야 한다(목표 5 — 역할 제한 없음)").isEqualTo(1);
    }

    // ── goal 8 — 위치 자동 첨부 · 캐시 비어도 발신은 성공 ────────────────────

    @Test
    void 위치_캐시가_있으면_신고에_좌표가_자동으로_붙는다() throws Exception {
        EmergencyFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, now());
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        위치를_기록한다(runId, "37.560000", "126.970000");

        mockMvc.perform(post(RAISE.formatted(runId))
                        .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(요청본문("accident", null, UUID.randomUUID())))
                .andExpect(status().isCreated());

        assertThat(신고_좌표(runId)).isEqualTo(new BigDecimal[] { new BigDecimal("37.560000"),
                new BigDecimal("126.970000") });
    }

    @Test
    void 위치_캐시가_비어도_신고_발신은_성공한다() throws Exception {
        EmergencyFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, now());
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        // Redis 에 아무것도 쓰지 않는다 — 안전 요구(목표 8): 캐시 미스가 발신 자체를 막으면 안 된다.

        mockMvc.perform(post(RAISE.formatted(runId))
                        .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(요청본문("accident", null, UUID.randomUUID())))
                .andExpect(status().isCreated());

        assertThat(신고_좌표_존재(runId)).as("캐시 미스는 좌표를 비운 채 성공해야지 발신 자체를 막으면 안 된다").isFalse();
    }

    /** BR-137 — 같은 {@code client_key} 재전송은 새 접수가 아니라 흡수라 §1.7 대로 200 이다(최초 접수만 201). */
    @Test
    void 같은_client_key_재전송은_200이고_최초_신고를_돌려준다() throws Exception {
        EmergencyFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long runId = fixtures.confirmedRun(academyId, fixtures.bus(academyId), now());
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        String token = 토큰(driverAccountId, academyId, Role.DRIVER);
        String body = 요청본문("accident", null, UUID.randomUUID());

        String first = mockMvc.perform(post(RAISE.formatted(runId)).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String firstId = com.jayway.jsonpath.JsonPath.read(first, "$.data.emergency_id");

        mockMvc.perform(post(RAISE.formatted(runId)).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.emergency_id").value(firstId))
                .andExpect(jsonPath("$.data.replayed").doesNotExist());

        assertThat(신고건수(runId)).isEqualTo(1);
    }

    /** BR-078 — 다른 학원 신고에 쓰인 {@code client_key} 가 겹치면 그 신고를 돌려주지 않고 거절한다. */
    @Test
    void 다른_학원_신고에_쓰인_client_key_를_재사용하면_422이고_남의_신고를_돌려주지_않는다() throws Exception {
        EmergencyFixtures fixtures = fixtures();
        long academyA = fixtures.academy();
        long runA = fixtures.confirmedRun(academyA, fixtures.bus(academyA), now());
        long driverA = fixtures.assignedManager(academyA, runA, ManagerRole.DRIVER, "기사A", now());
        long academyB = fixtures.academy();
        long runB = fixtures.confirmedRun(academyB, fixtures.bus(academyB), now());
        long driverB = fixtures.assignedManager(academyB, runB, ManagerRole.DRIVER, "기사B", now());
        UUID sharedKey = UUID.randomUUID();

        mockMvc.perform(post(RAISE.formatted(runA)).header("Authorization", 토큰(driverA, academyA, Role.DRIVER))
                        .contentType(MediaType.APPLICATION_JSON).content(요청본문("accident", null, sharedKey)))
                .andExpect(status().isCreated());
        mockMvc.perform(post(RAISE.formatted(runB)).header("Authorization", 토큰(driverB, academyB, Role.DRIVER))
                        .contentType(MediaType.APPLICATION_JSON).content(요청본문("accident", null, sharedKey)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    /** BR-039 — 캐시 <b>읽기 실패</b>(여기선 값 형식 불일치로 재현)도 미스와 같이 좌표 없이 접수돼야 한다. */
    @Test
    void 위치_캐시_읽기가_실패해도_신고_발신은_성공한다() throws Exception {
        EmergencyFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, now());
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        stringRedisTemplate.opsForValue().set("run:%d:position".formatted(runId), "{not-json");

        mockMvc.perform(post(RAISE.formatted(runId))
                        .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(요청본문("accident", null, UUID.randomUUID())))
                .andExpect(status().isCreated());

        assertThat(신고건수(runId)).as("신고 행이 롤백되지 않고 남는다").isEqualTo(1);
        assertThat(신고_좌표_존재(runId)).isFalse();
    }

    // ── goal 9 — 1분 이내 취소 · 경계값 ──────────────────────────────────

    @Test
    void 발신_직후_취소는_성공하고_취소시각이_기록된다() throws Exception {
        EmergencyFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, now());
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        long emergencyId = 신고를_발신한다(runId, driverAccountId, academyId);

        // BE-R1 목표 5 — API_SPEC §4.14 는 204 본문 없음을 요구한다. 이전에는 200 + 본문
        // canceled_at 이었다(정본과 어긋남).
        mockMvc.perform(delete(CANCEL.formatted(runId, emergencyId))
                        .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isNoContent())
                .andExpect(content().bytes(new byte[0]));

        assertThat(취소시각(emergencyId)).isNotNull();
    }

    @Test
    void 정확히_60초_경계는_포함이라_취소가_성공한다() throws Exception {
        EmergencyFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, now());
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        long emergencyId = 신고를_발신한다(runId, driverAccountId, academyId);
        접수시각을_옮긴다(emergencyId, now().minusSeconds(60));

        mockMvc.perform(delete(CANCEL.formatted(runId, emergencyId))
                        .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isNoContent());

        assertThat(취소시각(emergencyId)).as("경계값(정확히 60초)은 포함이라 성공해야 한다").isNotNull();
    }

    @Test
    void 급소_60초_1밀리초가_지나면_취소가_거절되고_취소시각이_남지_않는다() throws Exception {
        EmergencyFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, now());
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        long emergencyId = 신고를_발신한다(runId, driverAccountId, academyId);
        접수시각을_옮긴다(emergencyId, now().minusSeconds(61));

        mockMvc.perform(delete(CANCEL.formatted(runId, emergencyId))
                        .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EMERGENCY_CANCEL_WINDOW_CLOSED"));

        assertThat(취소시각(emergencyId)).as("창 밖이면 canceled_at 이 기록되면 안 된다").isNull();
    }

    // ── §4.15 — 발신한 비상 알림의 처리 상태 조회 ──────────────────────────

    @Test
    void 배치된_기사가_목록을_조회하면_응답_계약의_전_필드가_채워진다() throws Exception {
        EmergencyFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, now());
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        long emergencyId = 신고를_발신한다(runId, driverAccountId, academyId);

        mockMvc.perform(get(LIST.formatted(runId))
                        .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].emergency_id").value(String.valueOf(emergencyId)))
                .andExpect(jsonPath("$.data.items[0].type").value("accident"))
                .andExpect(jsonPath("$.data.items[0].raised_at").exists())
                .andExpect(jsonPath("$.data.items[0].cancelable_until").exists())
                .andExpect(jsonPath("$.data.items[0].acked").value(false))
                .andExpect(jsonPath("$.data.items[0].acked_at").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].acked_by_name").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].canceled_at").doesNotExist());
    }

    /**
     * 목록 응답의 {@code emergency_id} 가 <b>문자열</b>인지 타입으로 고정한다(BE-R1 수정 라운드 §1.4⑤
     * — 게이트가 직접 재현).
     *
     * <p><b>이 시험이 유일한 방어선이다.</b> 위 "전 필드" 시험의 {@code .value(String.valueOf(...))} 는
     * <b>타입을 가리지 않는다</b> — 필드를 {@code Long} 으로 되돌려도 그 단언은 그대로 통과한다.
     * 실제로 되돌려 확인한 값이며(FIX-EM 좌석 실측, 되돌렸을 때 실패한 것은 이 메서드 하나뿐이고
     * 나머지 14개는 통과했다), {@code isString()} 을 쓴 이 시험만 잡는다.
     * ⚠ 그러므로 <b>이 시험을 지우면 타입 회귀를 아무도 못 잡는다.</b>
     *
     * <p>왜 문자열이어야 하나 — {@code API_SPEC} 의 절별 응답 필드 표가 식별자를 {@code string} 으로
     * 적은 곳이 20곳이고 {@code number} 로 적은 곳은 0곳이다({@code §1.1} "식별자 | 서버 발급 문자열").
     * 매니저 앱({@code emergency_item.dart}) 이 {@code json['emergency_id'] as String} 로 파싱하므로,
     * 숫자로 나가면 목록이 비어 있지 않은 순간 비상 화면이 예외로 죽는다.
     */
    @Test
    void 목록_응답의_emergency_id는_문자열이다() throws Exception {
        EmergencyFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, now());
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        신고를_발신한다(runId, driverAccountId, academyId);

        mockMvc.perform(get(LIST.formatted(runId))
                        .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].emergency_id").isString());
    }

    /**
     * {@code cancelable_until} 이 <b>발신 시각 + 60초</b> 인지 값으로 고정한다(§4.15 · 취소 창 1분).
     * 위 "전 필드" 시험은 이 필드의 <b>존재</b>만 봐서, 발신 시각을 그대로 돌려주도록 바꿔도 통과했다
     * (조율자 실측 2026-09-09). 존재 검사는 필드가 사라지는 사고만 잡고 값이 틀리는 사고는 놓친다.
     */
    @Test
    void 취소_가능_시각은_발신_시각의_60초_뒤다() throws Exception {
        EmergencyFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, now());
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        long emergencyId = 신고를_발신한다(runId, driverAccountId, academyId);
        OffsetDateTime raisedAt = now().minusSeconds(30);
        접수시각을_옮긴다(emergencyId, raisedAt);

        String body = mockMvc.perform(get(LIST.formatted(runId))
                        .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        OffsetDateTime raised = 필드시각(body, "raised_at");
        OffsetDateTime cancelableUntil = 필드시각(body, "cancelable_until");
        assertThat(Duration.between(raised, cancelableUntil)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void 배치되지_않은_기사가_목록을_조회하면_403이다() throws Exception {
        EmergencyFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, now());
        long otherRunId = fixtures.confirmedRun(academyId, busId, now().plusHours(1));
        long outsiderAccountId = fixtures.assignedManager(academyId, otherRunId,
                ManagerRole.DRIVER, "다른회차기사", now());

        mockMvc.perform(get(LIST.formatted(runId))
                        .header("Authorization", 토큰(outsiderAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    /**
     * 존재하지 않는 회차·다른 학원 회차는 배치 확인 단계에서 걸러져 둘 다 403 이다(판단 근거 —
     * 보고서 항목: {@code RunAssignmentAccess#assertAssignedDriverOrEscort} 가 배치 확인을 회차
     * 존재 확인보다 먼저 하는 것이 이 모듈의 확립된 관례다, {@code ManagerRunAccess#requireAssignedRun}
     * 의 review-f3-r4 #38~44·Ruling 259(b) 와 {@code EmergencyCommandService#raise}·{@code #cancel}
     * 이 이미 같은 판단을 내려 두었다 — 404 를 응답에서 관측 가능하게 만들면 "배치되지 않은 회차"
     * 시나리오가 다시 가려지는 회귀를 재현하게 된다).
     */
    @Test
    void 존재하지_않는_회차를_지목하면_배치_확인에서_걸려_403이다() throws Exception {
        EmergencyFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long realRunId = fixtures.confirmedRun(academyId, busId, now());
        long driverAccountId = fixtures.assignedManager(academyId, realRunId, ManagerRole.DRIVER, "기사", now());
        long nonExistentRunId = realRunId + 999_999L;

        mockMvc.perform(get(LIST.formatted(nonExistentRunId))
                        .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void 확인_처리된_신고는_목록에_acked와_확인자_이름이_반영된다() throws Exception {
        EmergencyFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, now());
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        long staffAccountId = fixtures.staffAccount(academyId, "확인자");
        long emergencyId = 신고를_발신한다(runId, driverAccountId, academyId);

        mockMvc.perform(post(ACK.formatted(emergencyId))
                        .header("Authorization", 토큰(staffAccountId, academyId, Role.STAFF)))
                .andExpect(status().isOk());

        mockMvc.perform(get(LIST.formatted(runId))
                        .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].acked").value(true))
                .andExpect(jsonPath("$.data.items[0].acked_at").exists())
                .andExpect(jsonPath("$.data.items[0].acked_by_name").value("확인자"));
    }

    @Test
    void 취소된_신고는_목록에_취소시각이_반영된다() throws Exception {
        EmergencyFixtures fixtures = fixtures();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long runId = fixtures.confirmedRun(academyId, busId, now());
        long driverAccountId = fixtures.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now());
        long emergencyId = 신고를_발신한다(runId, driverAccountId, academyId);

        mockMvc.perform(delete(CANCEL.formatted(runId, emergencyId))
                        .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(LIST.formatted(runId))
                        .header("Authorization", 토큰(driverAccountId, academyId, Role.DRIVER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].canceled_at").exists());
    }

    // ── 픽스처 · 호출 도우미 ──────────────────────────────────────────────

    private String 토큰(long accountId, long academyId, Role role) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, role, AccountStatus.ACTIVE);
    }

    private String 요청본문(String type, String memo, UUID clientKey) {
        String memoJson = memo == null ? "null" : "\"" + memo + "\"";
        return "{\"type\":\"%s\",\"memo\":%s,\"client_key\":\"%s\"}".formatted(type, memoJson, clientKey);
    }

    private void 위치를_기록한다(long runId, String lat, String lng) {
        String key = "run:%d:position".formatted(runId);
        String json = """
                {"lat":%s,"lng":%s,"recordedAt":"%s","receivedAt":"%s","currentStopName":"정문"}"""
                .formatted(lat, lng, now(), now());
        stringRedisTemplate.opsForValue().set(key, json);
    }

    private long 신고를_발신한다(long runId, long accountId, long academyId) throws Exception {
        String body = mockMvc.perform(post(RAISE.formatted(runId))
                        .header("Authorization", 토큰(accountId, academyId, Role.DRIVER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(요청본문("accident", null, UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String emergencyId = com.jayway.jsonpath.JsonPath.read(body, "$.data.emergency_id");
        return Long.parseLong(emergencyId);
    }

    /**
     * 영속성 컨텍스트가 raise() 응답으로 이미 캐시해 둔 엔티티를 그대로 들고 있으면 이 SQL 이 반영되지
     * 않은 값을 서비스가 다시 읽는다({@code entityManager.clear()} 로 1차 캐시를 비워야 cancel() 의
     * 재조회가 이 값을 본다) — 첫 실행에서 61초 경계 시험이 200 으로 통과해 실제로 걸린 함정이다.
     */
    private void 접수시각을_옮긴다(long emergencyId, OffsetDateTime receivedAt) {
        jdbcTemplate.update("UPDATE emergency_alert SET received_at = ? WHERE id = ?", receivedAt, emergencyId);
        entityManager.clear();
    }

    /** 첫 항목의 시각 필드 하나를 꺼낸다 — 직렬화 문자열을 그대로 비교하면 오프셋 표기 차이에 걸린다. */
    private OffsetDateTime 필드시각(String responseBody, String field) throws Exception {
        return OffsetDateTime.parse(new ObjectMapper().readTree(responseBody)
                .path("data").path("items").get(0).path(field).asText());
    }

    private int 신고건수(long runId) {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM emergency_alert WHERE run_id = ?",
                Integer.class, runId);
        return count == null ? 0 : count;
    }

    private BigDecimal[] 신고_좌표(long runId) {
        return jdbcTemplate.queryForObject("SELECT lat, lng FROM emergency_alert WHERE run_id = ?",
                (rs, rowNum) -> new BigDecimal[] { rs.getBigDecimal("lat"), rs.getBigDecimal("lng") }, runId);
    }

    private boolean 신고_좌표_존재(long runId) {
        BigDecimal lat = jdbcTemplate.queryForObject("SELECT lat FROM emergency_alert WHERE run_id = ?",
                BigDecimal.class, runId);
        return lat != null;
    }

    private OffsetDateTime 취소시각(long emergencyId) {
        return jdbcTemplate.queryForObject("SELECT canceled_at FROM emergency_alert WHERE id = ?",
                OffsetDateTime.class, emergencyId);
    }
}
