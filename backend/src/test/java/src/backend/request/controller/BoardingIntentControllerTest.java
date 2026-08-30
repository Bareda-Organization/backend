package src.backend.request.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Role;
import src.backend.global.common.enums.Weekday;
import src.backend.global.security.JwtTokenProvider;
import src.backend.request.command.BoardingIntentFixtures;
import src.backend.routing.pipeline.RouteComputationPipeline;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.command.RunConfirmationFixtures;
import src.backend.run.command.RunConfirmationService;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * 탑승 의사 토글(ATT-01·02, P-03, API_SPEC §3.6) — Phase 8 목표 1·2·5·8·9(부분).
 *
 * <p>3구간 판정 자체({@link src.backend.request.domain.ChangeWindowPolicy})는 별도로 이미 단위
 * 시험이 있다고 가정하지 않는다 — 이 클래스가 그 판정을 <b>엔드포인트 경유로 실제로 타는지</b>까지
 * 함께 본다. {@link RouteComputationPipeline} 을 스파이로 감싸 ①·③ 어느 경로도 이를 부르지 않음을
 * 직접 확인한다(목표 1 — Ruling 198, "재최적화는 결과이지 동기 호출이 아니다").
 *
 * <p>목표 9 는 부분이다(Ruling 195) — {@code notification_log} 적재까지만 검사하고, 웹소켓 발행은
 * Phase 10 범위라 이 클래스가 단언하지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BoardingIntentControllerTest {

    private static final String INTENT = "/api/v1/students/%d/runs/%d/intent";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private Clock clock;

    @MockitoSpyBean
    private RouteComputationPipeline routeComputationPipeline;

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
    private ConfirmedRouteRepository confirmedRouteRepository;

    @Autowired
    private RouteVersionRepository routeVersionRepository;

    @Autowired
    private RunStopRepository runStopRepository;

    /**
     * 목표2(롤백) 시험이 이 저장소를 스파이로 감싸 {@code requestApproval} 의 응답 조립 직전 호출을
     * 겨냥해 강제로 예외를 던진다 — 나머지 시험은 실제 구현 그대로 위임되니 영향이 없다.
     */
    @MockitoSpyBean
    private RunRiderRepository runRiderRepository;

    @Autowired
    private RouteRepository routeRepository;

    @Autowired
    private RouteStopRepository routeStopRepository;

    @Autowired
    private WeeklyAddressRepository weeklyAddressRepository;

    /** 목표1 후반부 시험이 "확정 배치 1틱" 을 직접 실행하는 데 쓴다({@code RunConfirmationServiceTest} 와 같은 이유). */
    @Autowired
    private RunConfirmationService confirmationService;

    private BoardingIntentFixtures fixtures;

    private RunConfirmationFixtures confirmationFixtures;

    @TestConfiguration
    static class FixedClockConfig {

        private static final Instant FIXED = Instant.parse("2030-04-01T03:00:00Z"); // 2030-04-01 12:00 KST

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED, ZoneId.of("Asia/Seoul"));
        }
    }

    private BoardingIntentFixtures fixtures() {
        if (fixtures == null) {
            fixtures = new BoardingIntentFixtures(academyRepository, busRepository, studentRepository,
                    guardianRepository, guardianStudentRepository, accountRepository, academyStaffRepository,
                    runRepository, stopRepository, confirmedRouteRepository, routeVersionRepository,
                    runStopRepository, runRiderRepository);
        }
        return fixtures;
    }

    /** 확정 배치가 정상 경로로 읽어 낼 학원 좌표·노선·요일별 주소를 쌓는다 — {@code RunConfirmationServiceTest} 와 같은 헬퍼. */
    private RunConfirmationFixtures confirmationFixtures() {
        if (confirmationFixtures == null) {
            confirmationFixtures = new RunConfirmationFixtures(academyRepository, busRepository, routeRepository,
                    routeStopRepository, stopRepository, studentRepository, weeklyAddressRepository, runRepository);
        }
        return confirmationFixtures;
    }

    // ── 목표 1 — ①구간 즉시 반영, 재최적화 미호출 ──────────────────────────

    /**
     * ①구간에서 토글하면 응답이 즉시 바뀐 값을 싣고, {@link RouteComputationPipeline#compute} 는 <b>한
     * 번도</b> 불리지 않는다 — 이 회차는 아직 idle 이라 재최적화할 확정 노선 자체가 없다(Ruling 198).
     */
    @Test
    @DisplayName("목표1 — 즉시반영구간에서 토글하면 재최적화 없이 즉시 반영된다")
    void 즉시반영구간에서_토글하면_재최적화_없이_즉시_반영된다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long academyId = fixtures().academy();
        long busId = fixtures().bus(academyId);
        long studentId = fixtures().student(academyId, "학생1");
        BoardingIntentFixtures.GuardianAccount guardian1 = fixtures().guardian(academyId, "보호자1");
        long guardianAccountId = guardian1.accountId();
        fixtures().linkChild(guardian1.guardianId(), studentId, now.minusDays(1));
        long runId = fixtures().run(academyId, busId, now.plusHours(2), now.plusMinutes(90));

        mockMvc.perform(patch(INTENT.formatted(studentId, runId))
                        .header("Authorization", 토큰(guardianAccountId, academyId, Role.PARENT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"riding\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("applied"))
                .andExpect(jsonPath("$.data.riding").value(false))
                .andExpect(jsonPath("$.data.rider_status").value("absent"));

        verify(routeComputationPipeline, never()).compute(any());

        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT riding FROM boarding_intent WHERE run_id = ? AND student_id = ?", Boolean.class, runId,
                studentId)).isFalse();
    }

    /**
     * 목표1 후반부 — 위 시험은 토글 응답과 {@code boarding_intent.riding} 까지만 보고 끝나, 확정 배치를
     * 실제로 돌리는 코드가 하나도 없었다(게이트 리뷰 Important 1). 이 시험은 {@link
     * RunConfirmationService#confirmOne} 을 직접 1틱 실행해, ①구간에서 제외된 학생이 그 산출물에서
     * 실제로 빠지는지를 본다.
     *
     * <p>T3 게이트 리뷰가 저장 산출물({@code run_rider}·이 태스크에 대응하는 자리)만 보다가 노선 계산
     * 산출물({@code run_stop})의 사각지대를 놓친 전례를 따라, 이 시험도 <b>양쪽을 모두</b> 본다 —
     * {@code RunConfirmationService} 의 제외 필터가 {@code studentIds}(노선 계산 입력)와
     * {@code studentStops}(저장) 양쪽에 걸려 있으므로, 검사도 양쪽을 봐야 "명단에는 빠졌는데 버스는
     * 그 집에 들르는" 형태의 사각지대를 잡을 수 있다.
     */
    @Test
    @DisplayName("목표1(후반부) — ①구간 제외 학생은 확정 배치의 run_rider·run_stop 양쪽에서 빠진다")
    void 즉시반영구간_제외_학생은_확정배치_산출물_양쪽에서_빠진다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long academyId = confirmationFixtures().academyWithCoordinates();
        long busId = confirmationFixtures().bus(academyId);
        long excludedStop = confirmationFixtures().stop(academyId, "37.560000", "126.970000");
        long remainingStop = confirmationFixtures().stop(academyId, "37.561000", "126.971000");
        confirmationFixtures().route(academyId, busId, Weekday.MON, Direction.TO_ACADEMY, excludedStop,
                remainingStop);

        long excludedStudentId = confirmationFixtures().student(academyId, "제외학생");
        long remainingStudentId = confirmationFixtures().student(academyId, "잔류학생");
        confirmationFixtures().verifiedAddress(excludedStudentId, excludedStop, Weekday.MON, Direction.TO_ACADEMY,
                "37.560000", "126.970000");
        confirmationFixtures().verifiedAddress(remainingStudentId, remainingStop, Weekday.MON, Direction.TO_ACADEMY,
                "37.561000", "126.971000");

        BoardingIntentFixtures.GuardianAccount guardian = fixtures().guardian(academyId, "보호자10");
        fixtures().linkChild(guardian.guardianId(), excludedStudentId, now.minusDays(1));
        long runId = fixtures().run(academyId, busId, now.plusHours(3), now.plusMinutes(150));

        mockMvc.perform(patch(INTENT.formatted(excludedStudentId, runId))
                        .header("Authorization", 토큰(guardian.accountId(), academyId, Role.PARENT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"riding\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("applied"));

        entityManager.flush();
        confirmationService.confirmOne(runId);
        entityManager.flush();

        assertThat(jdbcTemplate.queryForList("SELECT student_id FROM run_rider WHERE run_id = ?", Long.class, runId))
                .as("확정 배치가 만드는 run_rider 명단에 제외 학생이 남아 있으면 안 된다")
                .containsExactly(remainingStudentId);

        Long versionId = jdbcTemplate.queryForObject(
                "SELECT current_version_id FROM confirmed_route WHERE run_id = ?", Long.class, runId);
        List<Long> runStopIds = jdbcTemplate.queryForList("SELECT stop_id FROM run_stop WHERE route_version_id = ?",
                Long.class, versionId);
        assertThat(runStopIds)
                .as("노선 계산 입력(run_stop)에서도 제외돼야 한다 — 저장(run_rider)만 보면 " + "\"명단에는 없는데 버스는 그 집에 들르는\" 사각지대를 놓친다")
                .doesNotContain(excludedStop)
                .contains(remainingStop);
    }

    // ── 목표 2 — ②구간 승인 대기, 기존값 응답 ───────────────────────────────

    /**
     * ②구간에서 토글하면 즉시 반영되지 않고 승인 대기 큐에 올라간다. 응답의 {@code riding} 은 <b>바뀌지
     * 않은 기존 값</b>이어야 한다(§3.6) — 실제 반영은 승인 이후다.
     */
    @Test
    @DisplayName("목표2 — 승인대기구간에서 토글하면 대기 접수되고 응답은 기존값을 유지한다")
    void 승인대기구간에서_토글하면_대기_접수되고_기존값을_응답한다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long academyId = fixtures().academy();
        long busId = fixtures().bus(academyId);
        long studentId = fixtures().student(academyId, "학생2");
        BoardingIntentFixtures.GuardianAccount guardian2 = fixtures().guardian(academyId, "보호자2");
        long guardianAccountId = guardian2.accountId();
        fixtures().linkChild(guardian2.guardianId(), studentId, now.minusDays(1));
        long runId = fixtures().run(academyId, busId, now.plusMinutes(20), now.minusMinutes(10));

        mockMvc.perform(patch(INTENT.formatted(studentId, runId))
                        .header("Authorization", 토큰(guardianAccountId, academyId, Role.PARENT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"riding\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("pending_approval"))
                .andExpect(jsonPath("$.data.riding").value(true))
                .andExpect(jsonPath("$.data.change_request_id").isNumber())
                .andExpect(jsonPath("$.data.change_quota_left").value(0));

        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT riding FROM boarding_intent WHERE run_id = ? AND student_id = ?", Boolean.class, runId,
                studentId)).as("②구간은 실제 반영 전이라 riding 이 그대로여야 한다").isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT change_used_count FROM boarding_intent WHERE run_id = ? AND student_id = ?", Integer.class,
                runId, studentId)).isEqualTo(1);

        Map<String, Object> changeRequest = jdbcTemplate.queryForMap(
                "SELECT source, type, status, window_segment FROM change_request WHERE run_id = ? AND student_id = ?",
                runId, studentId);
        assertThat(changeRequest.get("source")).isEqualTo("intent");
        assertThat(changeRequest.get("type")).isEqualTo("cancel");
        assertThat(changeRequest.get("status")).isEqualTo("pending");
        assertThat(((Number) changeRequest.get("window_segment")).shortValue()).isEqualTo((short) 2);
    }

    /**
     * 목표2 — 롤백 시 알림 미적재(게이트 리뷰 Important 2). {@code requestApproval} 은 {@code
     * ApprovalRequestedEvent} 를 발행한 뒤에도 응답을 조립하려고 {@link RunRiderRepository
     * #findByRunIdAndStudentId} 를 한 번 더 부른다(§3.6 순서상 마지막) — 그 호출을 스파이로 강제
     * 실패시켜 "리스너가 이미 알림을 적재했는데 그 뒤가 실패한" 상황을 재현한다.
     *
     * <p>기존 {@code NotificationOutboxTransactionTest} 는 {@code NotificationOutbox#append} 를
     * 직접 호출해 그 함수 자체의 원자성만 본다 — 이 시험처럼 "토글 → 이벤트 발행 →
     * {@code IntentNotificationListener} → 적재" 라는 이 엔드포인트 고유의 경로를 지나지 않는다.
     *
     * <p>이 클래스 전체가 {@code @Transactional} 이라 mockMvc 호출이 여는 서비스 트랜잭션은 기본값
     * (REQUIRED)이면 시험 트랜잭션에 합류한다 — 그러면 여기서 강제한 예외가 나도 실제 롤백은 시험
     * 종료 시점에야 일어나, 같은 트랜잭션 안의 SELECT 는 "아직 롤백되지 않은 자기 자신의 쓰기"를
     * 그대로 본다.
     *
     * <p>이 메서드만 {@link Propagation#NOT_SUPPORTED} 로 시험용 트랜잭션 자체를 끈다 — 그래야
     * 준비 데이터(학원·학생·회차 등)가 각자 자기 트랜잭션으로 실제 커밋되고, 뒤이은 mockMvc 호출도
     * 자기 트랜잭션에서 실제로 롤백된다. (REQUIRES_NEW 로 새 트랜잭션을 여는 방식은 시도했으나,
     * 그 새 트랜잭션은 별도 커넥션이라 아직 커밋되지 않은 준비 데이터를 보지 못해 요청 자체가
     * 실패했다 — 그래서 트랜잭션을 미루는 대신 아예 끈다.) 이 시험이 만든 행은 커밋된 채로
     * 남지만, 매번 새로 생성하는 식별자를 쓰므로 이후 실행과 충돌하지 않는다.
     */
    @Test
    @DisplayName("목표2 — 트랜잭션이 롤백되면 approval_requested 알림도 남지 않는다")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 승인대기_처리가_롤백되면_알림도_남지_않는다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long academyId = fixtures().academy();
        long busId = fixtures().bus(academyId);
        long studentId = fixtures().student(academyId, "학생10");
        long staffAccountId = fixtures().staffAccount(academyId);
        BoardingIntentFixtures.GuardianAccount guardian = fixtures().guardian(academyId, "보호자11");
        fixtures().linkChild(guardian.guardianId(), studentId, now.minusDays(1));
        long runId = fixtures().run(academyId, busId, now.plusMinutes(20), now.minusMinutes(10));

        doThrow(new RuntimeException("응답 조립 직전 실패를 대신한다 — 롤백을 강제한다"))
                .when(runRiderRepository).findByRunIdAndStudentId(runId, studentId);

        mockMvc.perform(patch(INTENT.formatted(studentId, runId))
                        .header("Authorization", 토큰(guardian.accountId(), academyId, Role.PARENT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"riding\":false}"))
                .andExpect(status().is5xxServerError());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM change_request WHERE run_id = ? AND student_id = ?", Integer.class, runId,
                studentId)).as("트랜잭션이 롤백됐으니 change_request 도 남지 않아야 한다").isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_log WHERE recipient_account_id = ? AND type = 'approval_requested' AND dedup_key LIKE ?",
                Integer.class, staffAccountId, "approval_requested:" + runId + ":%"))
                .as("리스너가 이벤트 발행 시점에 이미 적재를 시도했더라도, 트랜잭션이 롤백되면 그 행도 함께 사라져야 한다")
                .isZero();
    }

    // ── 목표 5 — 한도 소진 후 재요청은 403 ──────────────────────────────────

    /** ②구간 변경 한도는 회차당 1회다 — 소진 후 재요청은 {@code 403 CHANGE_LIMIT_REACHED}(목표 5). */
    @Test
    @DisplayName("목표5 — 한도 소진 후 승인대기 재요청은 403 CHANGE_LIMIT_REACHED 다")
    void 한도_소진_후_승인대기_재요청은_403_이다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long academyId = fixtures().academy();
        long busId = fixtures().bus(academyId);
        long studentId = fixtures().student(academyId, "학생3");
        BoardingIntentFixtures.GuardianAccount guardian3 = fixtures().guardian(academyId, "보호자3");
        long guardianAccountId = guardian3.accountId();
        fixtures().linkChild(guardian3.guardianId(), studentId, now.minusDays(1));
        long runId = fixtures().run(academyId, busId, now.plusMinutes(20), now.minusMinutes(10));
        String 토큰 = 토큰(guardianAccountId, academyId, Role.PARENT);

        mockMvc.perform(patch(INTENT.formatted(studentId, runId))
                        .header("Authorization", 토큰)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"riding\":false}"))
                .andExpect(status().isOk());

        mockMvc.perform(patch(INTENT.formatted(studentId, runId))
                        .header("Authorization", 토큰)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"riding\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CHANGE_LIMIT_REACHED"));

        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM change_request WHERE run_id = ? AND student_id = ?", Integer.class, runId,
                studentId)).as("소진 뒤 재요청은 두 번째 change_request 를 남기지 않는다").isEqualTo(1);

        // 한도는 boarding_intent 의 PK(run_id, student_id) 단위다 — 같은 학생이라도 다른 회차(하원
        // 회차 등)는 이 회차의 소진과 무관하게 여전히 200 이어야 한다. 이 단언이 없으면 한도를
        // "학생 단위"로 잘못 좁힌 구현(예: student_id 만으로 조회)도 그대로 통과한다.
        long anotherRunId = fixtures().run(academyId, busId, now.plusMinutes(25), now.minusMinutes(5));
        mockMvc.perform(patch(INTENT.formatted(studentId, anotherRunId))
                        .header("Authorization", 토큰)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"riding\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("pending_approval"));
    }

    // ── 목표 8 — ③구간 재최적화 없는 즉시 수용 ──────────────────────────────

    /**
     * ③구간(운행 시작 후 또는 출발 시각 도달)에서 {@code riding=false} 는 재최적화 없이 즉시 수용되고,
     * 그 정차지에 남은 탑승자가 0명이면 {@code run_stop.change='skipped'} 로 표시된다 — {@code seq} 는
     * 손대지 않는다(목표 8). {@code riding=true}(되돌리기)는 {@code 403 CHANGE_WINDOW_CLOSED} 다.
     */
    @Test
    @DisplayName("목표8 — 마감구간에서 riding=false는 재최적화 없이 수용되고 riding=true는 403이다")
    void 마감구간에서_미등원은_즉시수용되고_되돌리기는_403이다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long academyId = fixtures().academy();
        long busId = fixtures().bus(academyId);
        long studentId = fixtures().student(academyId, "학생4");
        BoardingIntentFixtures.GuardianAccount guardian4 = fixtures().guardian(academyId, "보호자4");
        long guardianAccountId = guardian4.accountId();
        fixtures().linkChild(guardian4.guardianId(), studentId, now.minusDays(1));
        long runId = fixtures().run(academyId, busId, now.minusMinutes(5), now.minusMinutes(35));
        long stopId = fixtures().stop(academyId, "37.560000", "126.970000");
        long runStopId = fixtures().confirmedSingleRiderStop(runId, studentId, stopId, now.minusHours(1));
        String 토큰 = 토큰(guardianAccountId, academyId, Role.PARENT);

        mockMvc.perform(patch(INTENT.formatted(studentId, runId))
                        .header("Authorization", 토큰)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"riding\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("applied_no_reroute"))
                .andExpect(jsonPath("$.data.riding").value(false));

        verify(routeComputationPipeline, never()).compute(any());

        entityManager.flush();
        Map<String, Object> runStop = jdbcTemplate.queryForMap("SELECT change, seq FROM run_stop WHERE id = ?",
                runStopId);
        assertThat(runStop.get("change")).isEqualTo("skipped");
        assertThat(runStop.get("seq")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM run_rider WHERE run_id = ? AND student_id = ?",
                String.class, runId, studentId)).isEqualTo("absent");

        Long versionIdAfterToggle = jdbcTemplate.queryForObject(
                "SELECT current_version_id FROM confirmed_route WHERE run_id = ?", Long.class, runId);
        assertThat(jdbcTemplate.queryForObject("SELECT version_no FROM route_version WHERE id = ?", Integer.class,
                versionIdAfterToggle)).as("③구간은 재최적화가 없으니 route_version 도 불변이어야 한다(목표 8)").isEqualTo(1);

        mockMvc.perform(patch(INTENT.formatted(studentId, runId))
                        .header("Authorization", 토큰)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"riding\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CHANGE_WINDOW_CLOSED"));
    }

    // ── 목표 9(부분) — 알림 적재(푸시만, 웹소켓은 Phase 10) ──────────────────

    /**
     * ①·②구간 모두 관계자에게 알림이 적재된다(API_SPEC §9.7, Ruling 195) — 여기서는
     * {@code notification_log} 적재만 본다. 웹소켓 발행은 이 태스크 범위 밖이라 단언하지 않는다.
     */
    @Test
    @DisplayName("목표9(부분) — 즉시반영·승인대기 모두 notification_log 에 적재된다")
    void 즉시반영과_승인대기_모두_알림이_적재된다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long academyId = fixtures().academy();
        long busId = fixtures().bus(academyId);
        long staffAccountId = fixtures().staffAccount(academyId);

        long immediateStudentId = fixtures().student(academyId, "학생5");
        BoardingIntentFixtures.GuardianAccount guardian5 = fixtures().guardian(academyId, "보호자5");
        long guardian5AccountId = guardian5.accountId();
        fixtures().linkChild(guardian5.guardianId(), immediateStudentId, now.minusDays(1));
        long immediateRunId = fixtures().run(academyId, busId, now.plusHours(2), now.plusMinutes(90));

        mockMvc.perform(patch(INTENT.formatted(immediateStudentId, immediateRunId))
                        .header("Authorization", 토큰(guardian5AccountId, academyId, Role.PARENT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"riding\":false}"))
                .andExpect(status().isOk());

        long approvalStudentId = fixtures().student(academyId, "학생6");
        BoardingIntentFixtures.GuardianAccount guardian6 = fixtures().guardian(academyId, "보호자6");
        long guardian6AccountId = guardian6.accountId();
        fixtures().linkChild(guardian6.guardianId(), approvalStudentId, now.minusDays(1));
        long approvalRunId = fixtures().run(academyId, busId, now.plusMinutes(20), now.minusMinutes(10));

        mockMvc.perform(patch(INTENT.formatted(approvalStudentId, approvalRunId))
                        .header("Authorization", 토큰(guardian6AccountId, academyId, Role.PARENT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"riding\":false}"))
                .andExpect(status().isOk());

        entityManager.flush();
        // notification_log.run_id 는 이 파이프라인(NotificationOutbox/NotificationDraft)이 채우지
        // 않는다 — RunRouteConfirmedNotificationTest 와 같은 이유로 dedup_key 접두 매칭으로 찾는다.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_log WHERE recipient_account_id = ? AND type = 'intent_changed' AND dedup_key LIKE ?",
                Integer.class, staffAccountId, "intent_changed:" + immediateRunId + ":%")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_log WHERE recipient_account_id = ? AND type = 'approval_requested' AND dedup_key LIKE ?",
                Integer.class, staffAccountId, "approval_requested:" + approvalRunId + ":%")).isEqualTo(1);
    }

    // ── 권한 · 학원 범위 ─────────────────────────────────────────────────

    /** {@code INTENT_WRITE} 는 학부모 전용이다 — 학생 토큰으로 호출하면 403 FORBIDDEN(§3.6 에러 표). */
    @Test
    @DisplayName("권한 — 학부모가 아닌 역할은 403 FORBIDDEN 이다")
    void 학부모가_아닌_역할은_403이다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long academyId = fixtures().academy();
        long busId = fixtures().bus(academyId);
        long studentId = fixtures().student(academyId, "학생7");
        long runId = fixtures().run(academyId, busId, now.plusHours(2), now.plusMinutes(90));

        mockMvc.perform(patch(INTENT.formatted(studentId, runId))
                        .header("Authorization", 토큰(90007L, academyId, Role.STUDENT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"riding\":false}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    /**
     * 연결 부재 자녀 — 이 보호자와 연결(link)되지 않은 학생을 지목하면 {@code 403 FORBIDDEN}(§3.7).
     * 이 판정은 {@link src.backend.student.access.GuardianChildAccess#assertLinkedChild} 를 새로
     * 만들지 않고 {@link src.backend.student.access.LinkedChildLookup} 경유로 재사용한다.
     */
    @Test
    @DisplayName("권한 — 연결 부재 자녀를 지목하면 403 FORBIDDEN 이다")
    void 연결_부재_자녀를_지목하면_403이다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long academyId = fixtures().academy();
        long busId = fixtures().bus(academyId);
        long studentId = fixtures().student(academyId, "학생9"); // 의도적으로 linkChild() 를 부르지 않는다
        BoardingIntentFixtures.GuardianAccount guardian9 = fixtures().guardian(academyId, "보호자9");
        long runId = fixtures().run(academyId, busId, now.plusHours(2), now.plusMinutes(90));

        mockMvc.perform(patch(INTENT.formatted(studentId, runId))
                        .header("Authorization", 토큰(guardian9.accountId(), academyId, Role.PARENT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"riding\":false}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    /**
     * T1 인계 사항 — {@code BoardingIntentRepository#findByRunIdAndStudentId} 가
     * {@code @AcademyScopeExempt} 인 전제("호출부가 이미 학원 소속을 확인했다")를 이 서비스가
     * 실제로 지킨다. 회차 조회를 {@link RunRepository#findByIdAndAcademyId} 로 학생의 학원에 직접
     * 좁히므로, 다른 학원의 회차를 지목하면 그 조회가 곧바로 빈 결과가 되어 {@code 404 RUN_NOT_FOUND}
     * 다 — 남의 학원 {@code boarding_intent} 행에 닿기 전에 차단된다.
     */
    @Test
    @DisplayName("학원 범위 — 다른 학원의 회차를 지목하면 404 RUN_NOT_FOUND 다")
    void 다른_학원의_회차를_지목하면_404이다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long academyA = fixtures().academy();
        long studentId = fixtures().student(academyA, "학생8");
        BoardingIntentFixtures.GuardianAccount guardian8 = fixtures().guardian(academyA, "보호자8");
        long guardianAccountId = guardian8.accountId();
        fixtures().linkChild(guardian8.guardianId(), studentId, now.minusDays(1));

        long academyB = fixtures().academy();
        long busB = fixtures().bus(academyB);
        long otherAcademyRunId = fixtures().run(academyB, busB, now.plusHours(2), now.plusMinutes(90));

        mockMvc.perform(patch(INTENT.formatted(studentId, otherAcademyRunId))
                        .header("Authorization", 토큰(guardianAccountId, academyA, Role.PARENT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"riding\":false}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_FOUND"));
    }

    private String 토큰(long accountId, long academyId, Role role) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, role, AccountStatus.ACTIVE);
    }
}
