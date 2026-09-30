package src.backend.run.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;

import src.backend.academy.repository.AcademyRepository;
import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Role;
import src.backend.global.common.enums.Weekday;
import src.backend.global.security.JwtTokenProvider;
import src.backend.global.websocket.WebSocketDestinations;
import src.backend.global.websocket.WebSocketEnvelope;
import src.backend.request.entity.ChangeRequest;
import src.backend.request.entity.ChangeRequestSource;
import src.backend.request.entity.ChangeRequestType;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.event.RunRouteConfirmedEvent;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * {@code route_changed} 방송 배선(Ruling 373, R36-BE2 목표 1·2·4·5) — 리스너가 {@code AFTER_COMMIT} 이라
 * 시험 트랜잭션을 씌우면 한 번도 불리지 않는다. 그래서 이 클래스는 {@code @Transactional} 없이 실제로 커밋하고
 * 만든 행은 표식 학원({@link RunConfirmationFixtures#ACADEMY_NAME}) 기준으로 직접 지운다
 * ({@code StaffApprovalDecideAtomicityTest} 와 같은 방식). 송신 템플릿만 가짜로 두어 실제로 나간 봉투를 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RunRouteChangedBroadcastWiringTest {

    private static final LocalDate SERVICE_DATE = LocalDate.of(2030, 5, 6); // 월요일

    private static final Weekday WEEKDAY = Weekday.MON;

    private static final ZoneOffset KST = ZoneOffset.of("+09:00");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @MockitoBean
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private RunConfirmationService confirmationService;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private BusRepository busRepository;

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
    private JdbcTemplate jdbcTemplate;

    private RunConfirmationFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new RunConfirmationFixtures(academyRepository, busRepository, routeRepository,
                routeStopRepository, stopRepository, studentRepository, weeklyAddressRepository, runRepository);
        cleanUpMarkedRows();
    }

    @AfterEach
    void tearDown() {
        reset(messagingTemplate);
        cleanUpMarkedRows();
    }

    /** waypoint → run_stop 이 RESTRICT 라 run_stop 을 먼저 지운 뒤 run 을 지운다. */
    private void cleanUpMarkedRows() {
        String academyIds = "(SELECT id FROM academy WHERE name = '" + RunConfirmationFixtures.ACADEMY_NAME + "')";
        jdbcTemplate.update("DELETE FROM notification_log WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM run_stop WHERE route_version_id IN (SELECT id FROM route_version "
                + "WHERE confirmed_route_id IN (SELECT id FROM run WHERE academy_id IN " + academyIds + "))");
        jdbcTemplate.update("DELETE FROM run WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM student WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM account WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM route WHERE bus_id IN (SELECT id FROM bus WHERE academy_id IN " + academyIds
                + ")");
        jdbcTemplate.update("DELETE FROM bus WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM stop WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM academy WHERE name = '" + RunConfirmationFixtures.ACADEMY_NAME + "'");
    }

    // ── 목표 1·2 — 커밋 뒤에만 나간다 ────────────────────────────────────

    @Test
    @DisplayName("커밋된 트랜잭션에서 발행하면 매니저 채널로 route_changed 1건이 나간다")
    void 커밋되면_방송된다() {
        OffsetDateTime confirmedAt = OffsetDateTime.now(KST);

        new TransactionTemplate(transactionManager)
                .executeWithoutResult(s -> eventPublisher.publishEvent(new RunRouteConfirmedEvent(77L, 1L, 2L, confirmedAt)));

        ArgumentCaptor<Object> envelope = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, times(1)).convertAndSend(eq(WebSocketDestinations.managerRun(77L)),
                envelope.capture());
        WebSocketEnvelope sent = (WebSocketEnvelope) envelope.getValue();
        assertThat(sent.event()).isEqualTo("route_changed");
        assertThat(sent.runId()).isEqualTo(77L);
    }

    @Test
    @DisplayName("롤백된 트랜잭션에서는 방송이 나가지 않는다")
    void 롤백되면_방송되지_않는다() {
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            eventPublisher.publishEvent(new RunRouteConfirmedEvent(78L, 1L, 2L, OffsetDateTime.now(KST)));
            s.setRollbackOnly();
        });

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    // ── 목표 4 — 실제 경로 2개 ───────────────────────────────────────────

    @Test
    @DisplayName("②구간 변경 승인(§5.6) 뒤에 route_changed 가 매니저 채널로 나간다")
    void 변경_승인_뒤_방송된다() throws Exception {
        승인_시나리오 s = 승인_시나리오를_만든다();

        결정한다(s).andExpect(status().isOk());

        assertThat(routeChangedTargets(s.runId())).containsExactly(WebSocketDestinations.managerRun(s.runId()));
    }

    @Test
    @DisplayName("경유 지점 배포(§5.15) 뒤에 route_changed 가 매니저 채널로 나간다")
    void 경유_지점_배포_뒤_방송된다() throws Exception {
        long academyId = 확정된_회차를_만든다();
        long runId = jdbcTemplate.queryForObject("SELECT id FROM run WHERE academy_id = ?", Long.class, academyId);
        clearInvocations(messagingTemplate);

        경유_지점을_배포한다(academyId, runId);

        assertThat(routeChangedTargets(runId)).containsExactly(WebSocketDestinations.managerRun(runId));
    }

    // ── 목표 5 — 방송 실패가 요청을 깨지 않는다 ────────────────────────────

    @Test
    @DisplayName("방송이 예외를 던져도 변경 승인 응답은 200 이고 결정은 커밋돼 있다")
    void 방송_실패해도_승인은_성공한다() throws Exception {
        승인_시나리오 s = 승인_시나리오를_만든다();
        doThrow(new IllegalStateException("브로커 다운")).when(messagingTemplate).convertAndSend(anyString(),
                any(Object.class));

        결정한다(s).andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM change_request WHERE id = ?", String.class,
                s.approvalId())).isEqualTo("approved");
    }

    /** 지금까지 나간 방송 중 {@code route_changed} 봉투의 목적지들. */
    private List<String> routeChangedTargets(long runId) {
        ArgumentCaptor<String> destinations = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> envelopes = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, atLeastOnce()).convertAndSend(destinations.capture(), envelopes.capture());
        return java.util.stream.IntStream.range(0, destinations.getAllValues().size())
                .filter(i -> "route_changed".equals(((WebSocketEnvelope) envelopes.getAllValues().get(i)).event()))
                .filter(i -> runId == ((WebSocketEnvelope) envelopes.getAllValues().get(i)).runId())
                .mapToObj(i -> destinations.getAllValues().get(i))
                .toList();
    }

    /** 확정 노선 위에 승인 대기 1건(학생2 취소)을 올린다 — 준비 중 나간 방송은 지워 기준선을 0 으로 둔다. */
    private 승인_시나리오 승인_시나리오를_만든다() {
        long academyId = 확정된_회차를_만든다();
        long runId = jdbcTemplate.queryForObject("SELECT id FROM run WHERE academy_id = ?", Long.class, academyId);
        long studentId = jdbcTemplate.queryForObject(
                "SELECT id FROM student WHERE academy_id = ? AND name = '학생2'", Long.class, academyId);
        long parentAccountId = accountRepository
                .save(Account.forSignup(academyId, "parent-" + runId, "hash", "학부모", "010-0000-0000", null,
                        Role.PARENT))
                .getId();
        long approvalId = changeRequestRepository.save(ChangeRequest.forRequest(academyId, runId, studentId,
                ChangeRequestSource.CHANGE_REQUEST, ChangeRequestType.CANCEL, (short) 2, parentAccountId,
                OffsetDateTime.now())).getId();
        clearInvocations(messagingTemplate);
        return new 승인_시나리오(academyId, runId, approvalId);
    }

    /** 정차지 3개 · 학생 3명이 붙은 확정 노선을 만들고 학원 id 를 돌려준다. */
    private long 확정된_회차를_만든다() {
        long academyId = fixtures.academyWithCoordinates();
        long busId = fixtures.bus(academyId);
        long firstStop = fixtures.stop(academyId, "37.560000", "126.970000");
        long midStop = fixtures.stop(academyId, "37.562000", "126.972000");
        long lastStop = fixtures.stop(academyId, "37.564000", "126.974000");
        fixtures.route(academyId, busId, WEEKDAY, Direction.TO_ACADEMY, firstStop, midStop, lastStop);
        long 학생1 = fixtures.student(academyId, "학생1");
        long 학생2 = fixtures.student(academyId, "학생2");
        long 학생3 = fixtures.student(academyId, "학생3");
        fixtures.verifiedAddress(학생1, firstStop, WEEKDAY, Direction.TO_ACADEMY, "37.560000", "126.970000");
        fixtures.verifiedAddress(학생2, midStop, WEEKDAY, Direction.TO_ACADEMY, "37.562000", "126.972000");
        fixtures.verifiedAddress(학생3, lastStop, WEEKDAY, Direction.TO_ACADEMY, "37.564000", "126.974000");
        OffsetDateTime departTime = SERVICE_DATE.atTime(8, 0).atOffset(KST);
        long runId = fixtures.idleRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        confirmationService.confirmOne(runId);
        return academyId;
    }

    private org.springframework.test.web.servlet.ResultActions 결정한다(승인_시나리오 s) throws Exception {
        String detail = mockMvc
                .perform(get("/api/v1/staff/approvals/" + s.approvalId()).header("Authorization", 토큰(s.academyId())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String previewToken = JsonPath.read(detail, "$.data.preview_token");
        return mockMvc.perform(post("/api/v1/staff/approvals/" + s.approvalId() + "/decide")
                .header("Authorization", 토큰(s.academyId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"approve\":true,\"preview_token\":\"" + previewToken + "\"}"));
    }

    /** §5.15 — 미리보기로 토큰을 받은 뒤 그 토큰으로 배포한다. */
    private void 경유_지점을_배포한다(long academyId, long runId) throws Exception {
        String path = "/api/v1/staff/runs/" + runId + "/waypoints";
        String body = "{\"address\":\"새경유로 20\",\"label\":\"긴급 정류장\",\"apply\":%s%s}";
        String preview = mockMvc.perform(post(path).header("Authorization", 토큰(academyId))
                        .contentType(MediaType.APPLICATION_JSON).content(body.formatted(false, "")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String token = JsonPath.read(preview, "$.data.preview_token");
        mockMvc.perform(post(path).header("Authorization", 토큰(academyId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(true, ",\"preview_token\":\"" + token + "\"")))
                .andExpect(status().isOk());
    }

    private String 토큰(long academyId) {
        return "Bearer " + tokenProvider.createAccessToken(academyId * 1000 + 1, academyId, Role.STAFF,
                AccountStatus.ACTIVE);
    }

    private record 승인_시나리오(long academyId, long runId, long approvalId) {
    }
}
