package src.backend.request.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import src.backend.academy.repository.AcademyRepository;
import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.common.enums.Weekday;
import src.backend.global.security.JwtTokenProvider;
import src.backend.manager.entity.Assignment;
import src.backend.manager.entity.Manager;
import src.backend.manager.entity.ManagerProfile;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.request.command.ChangeRequestAutoRejectionPersistence;
import src.backend.request.entity.ChangeRequest;
import src.backend.request.entity.ChangeRequestSource;
import src.backend.request.entity.ChangeRequestType;
import src.backend.request.preview.spec.ApprovalPreviewCache;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.routing.pipeline.RouteComputationPipeline;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.command.RunConfirmationFixtures;
import src.backend.run.command.RunConfirmationService;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * §5.6 {@code POST /staff/approvals/{id}/decide} — 승인·거절·창 닫힘·재처리·범위·부재를 본다
 * ({@link ChangeRequestDecisionServiceAtomicityTest} 는 이 클래스와 분리한다: 목표 7(실패 시 원자적
 * 롤백)은 결정 트랜잭션이 테스트 자신의 트랜잭션에 참여하면 같은 커넥션에서 <b>아직 실제로 롤백되지
 * 않은 플러시 결과</b>가 보여 검증이 거짓으로 통과하므로, 그 시험만 {@code @Transactional} 을 뺀
 * 별도 클래스에 둔다).
 *
 * <p><b>이 클래스의 최우선 단언은 두 갈래다</b> — 응답 필드·호출 횟수뿐 아니라 <b>실제로 배포된
 * 노선의 내용</b>({@code run_stop} 이 취소 대상 정류장을 정말 빼고 있는지)까지 본다. Phase 8 에서
 * 세 번 반복된 사각지대(응답은 맞는데 계산 결과는 안 보는 것)를 이 태스크에서 되풀이하지 않기 위해서다
 * (인수인계 §4).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StaffApprovalDecideControllerTest {

    private static final LocalDate SERVICE_DATE = LocalDate.of(2030, 5, 6); // 월요일

    private static final Weekday WEEKDAY = Weekday.MON;

    private static final ZoneOffset KST = ZoneOffset.of("+09:00");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private BusRepository busRepository;

    @Autowired
    private RunConfirmationService confirmationService;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private RouteRepository routeRepository;

    @Autowired
    private RouteStopRepository routeStopRepository;

    @Autowired
    private StopRepository stopRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private WeeklyAddressRepository weeklyAddressRepository;

    @Autowired
    private ChangeRequestRepository changeRequestRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ManagerRepository managerRepository;

    @Autowired
    private AssignmentRepository assignmentRepository;

    @Autowired
    private ApprovalPreviewCache previewCache;

    @Autowired
    private ChangeRequestAutoRejectionPersistence autoRejectionPersistence;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private RouteComputationPipeline pipeline;

    private RunConfirmationFixtures fixtures;

    private RunConfirmationFixtures fixtures() {
        if (fixtures == null) {
            fixtures = new RunConfirmationFixtures(academyRepository, busRepository, routeRepository,
                    routeStopRepository, stopRepository, studentRepository, weeklyAddressRepository, runRepository);
        }
        return fixtures;
    }

    // ── 목표 3 — 승인 ────────────────────────────────────────────────────

    /**
     * 승인하면 200 · {@code route_version} 이 직전+1 로 오르고 · <b>실제로 배포된 {@code run_stop}</b>
     * 이 취소 대상 정류장을 뺀 내용이며 · 기사에게 {@code route_changed}, 학부모에게
     * {@code change_decided} 가 각 1건 남는다. 응답 필드만으로는 "본 것과 배포된 것이 같다"만 보장될 뿐
     * "배포된 것이 옳다"는 보장되지 않으므로, {@code run_stop} 을 직접 읽어 대조한다.
     */
    @Test
    void 승인하면_노선이_실제로_반영되고_두_알림이_남는다() throws Exception {
        결정_시나리오 s = 정상_시나리오();
        String token = 미리보기_토큰_조회(s);
        clearInvocations(pipeline); // 미리보기 조회(GET) 가 이미 1회 호출해 둔 기록을 지운다.

        결정_요청(관계자_토큰(s.academyId), s.approvalId, 승인_바디(token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("approved"))
                .andExpect(jsonPath("$.data.stop_removed").value(true))
                .andExpect(jsonPath("$.data.route_version").value(2));

        verify(pipeline, times(0)).compute(any());

        Integer versionCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM route_version WHERE confirmed_route_id = ?", Integer.class, s.runId);
        assertThat(versionCount).as("승인은 새 노선 버전을 하나 더 쌓는다").isEqualTo(2);

        Long currentVersionId = jdbcTemplate.queryForObject(
                "SELECT current_version_id FROM confirmed_route WHERE run_id = ?", Long.class, s.runId);
        List<Long> stopIds = jdbcTemplate.queryForList(
                "SELECT stop_id FROM run_stop WHERE route_version_id = ? ORDER BY seq", Long.class,
                currentVersionId);
        assertThat(stopIds).as("취소된 학생(midStop)의 정류장이 실제로 배포된 노선에서 빠져야 한다")
                .containsExactly(s.firstStopId, s.lastStopId);

        // run_stop(배포된 노선)만 보면 target.markAbsent(...) 자체가 커밋됐는지는 못 본다 — 그 학생의
        // 명단 상태(run_rider.status)를 직접 확인한다.
        String riderStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM run_rider WHERE run_id = ? AND stop_id = ?", String.class, s.runId,
                s.midStopId);
        assertThat(riderStatus).as("취소된 학생의 명단 상태가 absent 로 남아야 한다").isEqualTo("absent");

        Integer routeChangedCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_log WHERE recipient_account_id = ? AND type = 'route_changed'",
                Integer.class, s.driverAccountId);
        assertThat(routeChangedCount).as("배치된 기사에게 route_changed 가 남아야 한다").isEqualTo(1);

        String decidedBody = jdbcTemplate.queryForObject(
                "SELECT body FROM notification_log WHERE recipient_account_id = ? AND type = 'change_decided'",
                String.class, s.parentAccountId);
        assertThat(decidedBody).as("학부모에게 승인 안내가 남아야 한다").contains("승인되어 반영");

        assertThat(previewCache.find(s.approvalId)).as("결정 후 미리보기 캐시는 비어 있어야 한다").isEmpty();
    }

    // ── 목표 4 — 거절 ────────────────────────────────────────────────────

    /**
     * 거절하면 재최적화를 전혀 부르지 않고(호출 0회 · {@code route_version} 불변 · {@code run_stop}
     * 내용 불변) 사유가 학부모 알림 본문에 그대로 실린다.
     */
    @Test
    void 거절하면_노선을_건드리지_않고_사유가_알림에_실린다() throws Exception {
        결정_시나리오 s = 정상_시나리오();
        미리보기_토큰_조회(s); // 캐시를 채워도 거절 경로는 그 캐시를 쓰지 않는다는 것까지 함께 본다.
        clearInvocations(pipeline); // 위 GET 이 이미 1회 호출해 둔 기록을 지운다.
        String 사유 = "학부모가 직접 등원시키기로 함";

        결정_요청(관계자_토큰(s.academyId), s.approvalId, 거절_바디(사유))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("rejected"))
                .andExpect(jsonPath("$.data.stop_removed").value(false))
                .andExpect(jsonPath("$.data.route_version").value(1));

        verify(pipeline, times(0)).compute(any());

        Integer versionCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM route_version WHERE confirmed_route_id = ?", Integer.class, s.runId);
        assertThat(versionCount).as("거절은 재최적화를 부르지 않으므로 노선 버전이 늘지 않아야 한다").isEqualTo(1);

        Long currentVersionId = jdbcTemplate.queryForObject(
                "SELECT current_version_id FROM confirmed_route WHERE run_id = ?", Long.class, s.runId);
        List<Long> stopIds = jdbcTemplate.queryForList(
                "SELECT stop_id FROM run_stop WHERE route_version_id = ? ORDER BY seq", Long.class,
                currentVersionId);
        assertThat(stopIds).as("거절은 기존 노선을 그대로 유지해야 한다")
                .containsExactly(s.firstStopId, s.midStopId, s.lastStopId);

        Integer routeChangedCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_log WHERE recipient_account_id = ? AND type = 'route_changed'",
                Integer.class, s.driverAccountId);
        assertThat(routeChangedCount).as("거절은 기사에게 route_changed 를 남기지 않는다").isZero();

        String decidedBody = jdbcTemplate.queryForObject(
                "SELECT body FROM notification_log WHERE recipient_account_id = ? AND type = 'change_decided'",
                String.class, s.parentAccountId);
        assertThat(decidedBody).as("거절 사유가 알림 본문에 그대로 실려야 한다").contains(사유);

        assertThat(previewCache.find(s.approvalId)).isEmpty();
    }

    /** 거절인데 사유가 없으면 {@code 422 VALIDATION_FAILED}. */
    @Test
    void 거절_사유가_없으면_422_이다() throws Exception {
        결정_시나리오 s = 정상_시나리오();

        결정_요청(관계자_토큰(s.academyId), s.approvalId, 거절_바디(null))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    // ── preview_token ────────────────────────────────────────────────────

    /** {@code preview_token} 이 아예 없으면(승인은 필수) {@code 409 PREVIEW_STALE}. */
    @Test
    void preview_token_이_없으면_409_이다() throws Exception {
        결정_시나리오 s = 정상_시나리오();
        미리보기_토큰_조회(s);

        결정_요청(관계자_토큰(s.academyId), s.approvalId, 승인_바디(null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PREVIEW_STALE"));
    }

    /** {@code preview_token} 이 틀리면 {@code 409 PREVIEW_STALE}. */
    @Test
    void preview_token_이_틀리면_409_이다() throws Exception {
        결정_시나리오 s = 정상_시나리오();
        미리보기_토큰_조회(s);

        결정_요청(관계자_토큰(s.academyId), s.approvalId, 승인_바디("가짜-토큰"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PREVIEW_STALE"));
    }

    /**
     * 토큰 문자열이 일치해도 <b>그 사이 같은 회차의 다른 승인 건이 먼저 결정</b>돼 명단이 바뀌면
     * 지문이 어긋나 {@code 409 PREVIEW_STALE} 이어야 한다 — 게이트 리뷰가 재현한 시나리오(대기 A·B
     * 두 건 → 둘 다 미리보기 조회 → A 승인 → B 의 토큰은 A 승인 이전 것) 그대로다. 지문 대조가 없으면
     * B 도 200 으로 통과해 A 가 이미 지운 정류장이 되살아난다.
     */
    @Test
    void 다른_승인_건이_먼저_결정되면_지문이_어긋나_409_이다() throws Exception {
        결정_시나리오 s = 정상_시나리오(); // A = s.approvalId(학생2, midStop 취소)
        ChangeRequest 두번째요청 = ChangeRequest.forRequest(s.academyId, s.runId, s.lastStudentId,
                ChangeRequestSource.CHANGE_REQUEST, ChangeRequestType.CANCEL, (short) 2, s.parentAccountId,
                OffsetDateTime.now());
        long approvalIdB = changeRequestRepository.save(두번째요청).getId(); // B(학생3, lastStop 취소)

        String tokenA = 미리보기_토큰_조회(s);
        String tokenB = 미리보기_토큰_조회(approvalIdB, s.academyId); // A 승인 이전에 발급된 토큰
        clearInvocations(pipeline);

        결정_요청(관계자_토큰(s.academyId), s.approvalId, 승인_바디(tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("approved"));

        결정_요청(관계자_토큰(s.academyId), approvalIdB, 승인_바디(tokenB))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PREVIEW_STALE"));

        Long currentVersionId = jdbcTemplate.queryForObject(
                "SELECT current_version_id FROM confirmed_route WHERE run_id = ?", Long.class, s.runId);
        List<Long> stopIds = jdbcTemplate.queryForList(
                "SELECT stop_id FROM run_stop WHERE route_version_id = ? ORDER BY seq", Long.class,
                currentVersionId);
        assertThat(stopIds).as("A 승인 결과만 반영돼야 한다 — B 가 지우려던 lastStop 이 되살아나면 안 된다")
                .containsExactly(s.firstStopId, s.lastStopId);
    }

    // ── 창 · 재처리 · 격리 · 부재 ────────────────────────────────────────

    /**
     * 창이 닫힌(운행 시작 시각이 이미 지난) 뒤 도달한 결정은 {@code 403 CHANGE_WINDOW_CLOSED} —
     * 409 가 아니다(Ruling 200, {@code §5.6} 정본 정정).
     */
    @Test
    void 창이_닫힌_뒤_도달한_결정은_403_이다() throws Exception {
        결정_시나리오 s = 창이_닫힌_시나리오();

        결정_요청(관계자_토큰(s.academyId), s.approvalId, 거절_바디("아무 사유"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CHANGE_WINDOW_CLOSED"));
    }

    // 이미 처리된 건을 다시 결정하면 409 APPROVAL_ALREADY_DECIDED — 이 검증은 여기 두지 않는다.
    // 첫 결정의 dirty-check UPDATE(cr.approve(...))는 @Transactional 시험의 커넥션이 실제로
    // 커밋될 때까지 플러시가 미뤄져(신규 INSERT·flushAutomatically UPDATE 와 달리), 같은 시험
    // 트랜잭션 안에서 이어지는 두 번째 요청이 여전히 대기(pending) 상태를 본다 — assertPending() 이
    // 통과해 버려 재처리 차단이 실제로는 검증되지 않는다(직접 실측: 원시 SQL·JPA findById 모두 첫
    // 결정 뒤에도 pending 이었다). 실제 커밋이 필요해 StaffApprovalDecideAtomicityTest 로 옮겼다.

    /** 다른 학원 관계자가 결정을 시도하면 {@code 403 ACADEMY_SCOPE_VIOLATION}. */
    @Test
    void 다른_학원_관계자가_결정하면_403_이다() throws Exception {
        결정_시나리오 s = 정상_시나리오();
        long 남의학원 = fixtures().academyWithCoordinates();

        결정_요청(관계자_토큰(남의학원), s.approvalId, 거절_바디("아무 사유"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACADEMY_SCOPE_VIOLATION"));

        verify(pipeline, times(0)).compute(any());
    }

    /** 존재하지 않는 승인 건은 {@code 404 APPROVAL_NOT_FOUND}. */
    @Test
    void 존재하지_않는_승인_건은_404_이다() throws Exception {
        long academyId = fixtures().academyWithCoordinates();

        결정_요청(관계자_토큰(academyId), 999_999_999L, 거절_바디("아무 사유"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("APPROVAL_NOT_FOUND"));
    }

    // ── 미리보기 캐시 제거 — 자동 거절 경로 ─────────────────────────────

    /**
     * 자동 거절({@code ChangeRequestAutoRejectionPersistence.autoRejectOne})도 승인·거절과 같이
     * 미리보기 캐시를 지워야 한다 — 세 종결 경로 중 이 경로의 배선은 이 태스크(T5)의 몫이다
     * ({@link ApprovalPreviewCache#evict} 자바독). 인메모리 캐시라 트랜잭션 경계와 무관하게 즉시
     * 관측된다.
     */
    @Test
    void 자동_거절_후에도_미리보기_캐시가_비어_있어야_한다() throws Exception {
        결정_시나리오 s = 정상_시나리오();
        미리보기_토큰_조회(s);
        assertThat(previewCache.find(s.approvalId)).as("자동 거절 전에는 캐시가 채워져 있어야 정상 시나리오다")
                .isPresent();

        boolean rejected = autoRejectionPersistence.autoRejectOne(s.approvalId, OffsetDateTime.now(KST));

        assertThat(rejected).isTrue();
        assertThat(previewCache.find(s.approvalId)).as("자동 거절 후 미리보기 캐시는 비어 있어야 한다").isEmpty();
    }

    // ── 픽스처 ────────────────────────────────────────────────────────────

    /**
     * 확정 노선 위에 승인 대기 1건 + 기사 배치 + 학부모 계정을 올린 시나리오. {@link
     * StaffApprovalControllerTest#확정된_회차와_승인_대기_건을_만든다} 와 정차지 배치는 같지만, 결정
     * 결과 알림 수신자(기사·학부모 계정)를 추가로 필요로 해 별도로 둔다.
     */
    private 결정_시나리오 결정_시나리오를_만든다(LocalDate serviceDate, Weekday weekday, OffsetDateTime departTime)
            throws Exception {
        long academyId = fixtures().academyWithCoordinates();
        long busId = fixtures().bus(academyId);
        long firstStop = fixtures().stop(academyId, "37.560000", "126.970000");
        long midStop = fixtures().stop(academyId, "37.562000", "126.972000");
        long lastStop = fixtures().stop(academyId, "37.564000", "126.974000");
        fixtures().route(academyId, busId, weekday, Direction.TO_ACADEMY, firstStop, midStop, lastStop);

        long 학생1 = fixtures().student(academyId, "학생1");
        long 학생2 = fixtures().student(academyId, "학생2");
        long 학생3 = fixtures().student(academyId, "학생3");
        fixtures().verifiedAddress(학생1, firstStop, weekday, Direction.TO_ACADEMY, "37.560000", "126.970000");
        fixtures().verifiedAddress(학생2, midStop, weekday, Direction.TO_ACADEMY, "37.562000", "126.972000");
        fixtures().verifiedAddress(학생3, lastStop, weekday, Direction.TO_ACADEMY, "37.564000", "126.974000");

        long runId = fixtures().idleRun(academyId, busId, serviceDate, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        confirmationService.confirmOne(runId);
        org.mockito.Mockito.clearInvocations(pipeline);

        long parentAccountId = accountRepository
                .save(Account.forSignup(academyId, "parent-" + runId, "hash", "학부모", "010-0000-0000", null,
                        Role.PARENT))
                .getId();

        long driverAccountId = accountRepository
                .save(Account.forSignup(academyId, "driver-" + runId, "hash", "기사", "010-1111-1111", null,
                        Role.DRIVER))
                .getId();
        Manager driver = Manager.register(academyId,
                new ManagerProfile("기사", "010-1111-1111", ManagerRole.DRIVER, null));
        driver.linkAccount(driverAccountId);
        long driverId = managerRepository.save(driver).getId();
        assignmentRepository.save(
                Assignment.uponAssignment(runId, driverId, ManagerRole.DRIVER, OffsetDateTime.now(), driverAccountId));

        ChangeRequest changeRequest = ChangeRequest.forRequest(academyId, runId, 학생2,
                ChangeRequestSource.CHANGE_REQUEST, ChangeRequestType.CANCEL, (short) 2, parentAccountId,
                OffsetDateTime.now());
        long approvalId = changeRequestRepository.save(changeRequest).getId();

        return new 결정_시나리오(academyId, runId, approvalId, firstStop, midStop, lastStop, parentAccountId,
                driverAccountId, 학생3);
    }

    private 결정_시나리오 정상_시나리오() throws Exception {
        OffsetDateTime departTime = SERVICE_DATE.atTime(8, 0).atOffset(KST);
        return 결정_시나리오를_만든다(SERVICE_DATE, WEEKDAY, departTime);
    }

    /** 출발 시각이 이미 지난 시나리오 — {@link RunConfirmationService#confirmOne} 은 시각 전제가 없어 그대로 확정된다. */
    private 결정_시나리오 창이_닫힌_시나리오() throws Exception {
        OffsetDateTime departTime = OffsetDateTime.now(KST).minusHours(2);
        LocalDate serviceDate = departTime.toLocalDate();
        Weekday weekday = Weekday.valueOf(departTime.getDayOfWeek().name().substring(0, 3));
        return 결정_시나리오를_만든다(serviceDate, weekday, departTime);
    }

    private String 미리보기_토큰_조회(결정_시나리오 s) throws Exception {
        return 미리보기_토큰_조회(s.approvalId, s.academyId);
    }

    private String 미리보기_토큰_조회(long approvalId, long academyId) throws Exception {
        String body = mockMvc
                .perform(get("/api/v1/staff/approvals/" + approvalId).header("Authorization", 관계자_토큰(academyId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$.data.preview_token");
    }

    private String 승인_바디(String token) {
        return token == null ? "{\"approve\":true}" : "{\"approve\":true,\"preview_token\":\"" + token + "\"}";
    }

    private String 거절_바디(String reason) {
        return reason == null ? "{\"approve\":false}" : "{\"approve\":false,\"reject_reason\":\"" + reason + "\"}";
    }

    private ResultActions 결정_요청(String token, long approvalId, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/staff/approvals/" + approvalId + "/decide")
                .header("Authorization", token)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String 관계자_토큰(long academyId) {
        return "Bearer " + tokenProvider.createAccessToken(academyId * 1000 + 1, academyId, Role.STAFF,
                AccountStatus.ACTIVE);
    }

    /** 결정 시나리오 픽스처 값 묶음. */
    private static final class 결정_시나리오 {
        final long academyId;
        final long runId;
        final long approvalId;
        final long firstStopId;
        final long midStopId;
        final long lastStopId;
        final long parentAccountId;
        final long driverAccountId;
        final long lastStudentId; // lastStop 에 탄 학생3 — 지문 재검증 시험이 두 번째 승인 대기 건을 만들 때 쓴다.

        결정_시나리오(long academyId, long runId, long approvalId, long firstStopId, long midStopId, long lastStopId,
                long parentAccountId, long driverAccountId, long lastStudentId) {
            this.academyId = academyId;
            this.runId = runId;
            this.approvalId = approvalId;
            this.firstStopId = firstStopId;
            this.midStopId = midStopId;
            this.lastStopId = lastStopId;
            this.parentAccountId = parentAccountId;
            this.driverAccountId = driverAccountId;
            this.lastStudentId = lastStudentId;
        }
    }
}
