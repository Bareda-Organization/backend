package src.backend.request.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import src.backend.academy.repository.AcademyRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Role;
import src.backend.global.common.enums.Weekday;
import src.backend.global.security.JwtTokenProvider;
import src.backend.request.entity.BoardingIntent;
import src.backend.request.entity.ChangeRequest;
import src.backend.request.entity.ChangeRequestSource;
import src.backend.request.entity.ChangeRequestType;
import src.backend.request.repository.BoardingIntentRepository;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.command.RunConfirmationFixtures;
import src.backend.run.command.RunConfirmationService;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * {@code @Transactional} 을 쓰지 않는 결정 시험 묶음 — 두 가지 이유로 실제 커밋이 필요하다.
 *
 * <p><b>목표 7(재최적화 저장 실패 시 원자적 롤백)</b> — {@link src.backend.request.command.
 * ChangeRequestDecisionService#decide} 는 그 자체가 {@code @Transactional} 이라, 이 시험 클래스까지
 * {@code @Transactional} 이면 결정 트랜잭션이 <b>테스트 트랜잭션에 참여</b>하게 되어 예외가 나도
 * "롤백 전용" 표시만 남고 실제 {@code ROLLBACK} 은 테스트 메서드가 끝난 뒤에야 실행된다 — 그 사이에
 * 같은 커넥션으로 읽으면 이미 플러시된(아직 롤백되지 않은) 값이 보여 "롤백됐다"는 단언이 실제로는
 * 아무것도 검증하지 못한다({@code ChangeRequestAutoRejectionServiceTest} 가 같은 이유로 이미
 * {@code @Transactional} 을 뺀 전례를 따른다).
 *
 * <p><b>이미 처리된 건의 재처리 차단</b> — 같은 이유의 반대쪽이다. {@code @Transactional} 시험
 * 안에서 결정을 두 번 잇달아 호출하면, 첫 결정의 {@code cr.approve(...)} 같은 단순 dirty-check UPDATE
 * 는 트랜잭션이 실제로 끝날 때까지 플러시가 미뤄져(신규 INSERT·{@code flushAutomatically} UPDATE 와
 * 달리) 두 번째 요청이 여전히 대기(pending) 상태를 본다 — {@code assertPending()} 가 통과해 버려 재처리
 * 차단이 실제로는 검증되지 않는다(직접 실측: 같은 커넥션의 원시 SQL과 JPA {@code findById} 모두 첫
 * 결정 뒤에도 상태가 {@code pending} 으로 남아 있었다). {@code @Transactional} 을 빼면 각 결정 호출이
 * 자신의 트랜잭션을 <b>직접 소유</b>해 곧바로 커밋되므로, 두 번째 요청이 진짜로 갱신된 상태를 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StaffApprovalDecideAtomicityTest {

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
    private BoardingIntentRepository boardingIntentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private RunStopRepository runStopRepository;

    private RunConfirmationFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new RunConfirmationFixtures(academyRepository, busRepository, routeRepository,
                routeStopRepository, stopRepository, studentRepository, weeklyAddressRepository, runRepository);
        cleanUpMarkedRows();
    }

    @AfterEach
    void tearDown() {
        reset(runStopRepository);
        cleanUpMarkedRows();
    }

    private void cleanUpMarkedRows() {
        String academyIds = "(SELECT id FROM academy WHERE name = '" + RunConfirmationFixtures.ACADEMY_NAME + "')";
        jdbcTemplate.update("DELETE FROM notification_log WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM run WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM student WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM account WHERE academy_id IN " + academyIds);
        // route 가 bus 를 RESTRICT 로 참조한다(fk_route_bus) — bus 를 지우기 전에 route 부터 치운다
        // (route_stop 은 route→CASCADE 라 route 삭제만으로 함께 정리된다).
        jdbcTemplate.update("DELETE FROM route WHERE bus_id IN (SELECT id FROM bus WHERE academy_id IN " + academyIds
                + ")");
        jdbcTemplate.update("DELETE FROM bus WHERE academy_id IN " + academyIds);
        // stop 도 academy 를 RESTRICT 로 참조한다(fk_stop_academy) — route_stop·run_stop·run_rider 는
        // route·run 삭제로 이미 함께 치워졌으니 남은 것은 stop 자신뿐이다.
        jdbcTemplate.update("DELETE FROM stop WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM academy WHERE name = '" + RunConfirmationFixtures.ACADEMY_NAME + "'");
    }

    /**
     * {@link RunStopRepository#saveAll} — {@code assignCurrentVersion}(즉시 flush 되는 UPDATE)이
     * <b>이미 실행된 뒤</b>인 지점에 예외를 심는다. 그런데도 최종 결과가 옛 상태 그대로라면, "일부는
     * 이미 DB 에 반영됐지만 트랜잭션이 커밋되지 않아 전부 되돌아간" 진짜 원자성을 본 것이다.
     */
    @Test
    void 재최적화_저장이_실패하면_전부_롤백된다() throws Exception {
        결정_시나리오 s = 결정_시나리오를_만든다();

        // 이미 한도를 소진해 둔 상태로 시작한다 — 실패한 결정이 이 값을 건드리지 않아야 한다(그대로
        // 소진된 채 남아야 한다. 되돌리는 것은 오직 자동 거절 경로의 몫이다).
        BoardingIntent intent = BoardingIntent.forRun(s.runId, s.studentId, OffsetDateTime.now(KST));
        intent.consumeChangeQuota();
        boardingIntentRepository.save(intent);

        long academyId = s.academyId;
        long runId = s.runId;
        long approvalId = s.approvalId;

        String token = 미리보기_토큰_조회(academyId, approvalId);

        Long versionIdBefore = jdbcTemplate.queryForObject(
                "SELECT current_version_id FROM confirmed_route WHERE run_id = ?", Long.class, runId);

        doThrow(new RuntimeException("의도적 실패 — 재최적화 산출물 저장 단계")).when(runStopRepository).saveAll(anyList());

        결정_요청(academyId, approvalId, "{\"approve\":true,\"preview_token\":\"" + token + "\"}")
                .andExpect(status().isInternalServerError());

        String changeRequestStatus = jdbcTemplate.queryForObject("SELECT status FROM change_request WHERE id = ?",
                String.class, approvalId);
        assertThat(changeRequestStatus).as("실패하면 요청은 대기 상태로 남아야 한다").isEqualTo("pending");

        Long versionIdAfter = jdbcTemplate.queryForObject(
                "SELECT current_version_id FROM confirmed_route WHERE run_id = ?", Long.class, runId);
        assertThat(versionIdAfter).as("실패하면 assignCurrentVersion 의 UPDATE 도 되돌아가야 한다")
                .isEqualTo(versionIdBefore);

        Integer versionCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM route_version WHERE confirmed_route_id = ?", Integer.class, runId);
        assertThat(versionCount).as("실패하면 새 노선 버전 행 자체가 남지 않아야 한다").isEqualTo(1);

        Integer usedCount = jdbcTemplate.queryForObject(
                "SELECT change_used_count FROM boarding_intent WHERE run_id = ? AND student_id = ?", Integer.class,
                runId, s.studentId);
        assertThat(usedCount).as("실패한 결정은 한도를 건드리지 않는다(그대로 소진된 채 남는다)").isEqualTo(1);

        Integer notificationCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_log WHERE academy_id = ?", Integer.class, academyId);
        assertThat(notificationCount).as("실패한 결정은 어떤 알림도 남기지 않아야 한다").isZero();
    }

    /**
     * 이미 처리된 건을 다시 결정하면 {@code 409 APPROVAL_ALREADY_DECIDED} — 첫 결정이 <b>실제로
     * 커밋된 뒤</b>라야 두 번째 요청이 그 상태를 볼 수 있다(클래스 javadoc 둘째 문단).
     */
    @Test
    void 이미_처리된_건을_다시_결정하면_409_이다() throws Exception {
        결정_시나리오 s = 결정_시나리오를_만든다();
        String token = 미리보기_토큰_조회(s.academyId, s.approvalId);

        결정_요청(s.academyId, s.approvalId, "{\"approve\":true,\"preview_token\":\"" + token + "\"}")
                .andExpect(status().isOk());

        // assignCurrentVersion 의 clearAutomatically 가 cr 을 준영속으로 만드는 지점이라, 첫 결정이
        // 실제로 DB 에 반영됐는지 여기서 직접 확인한다(상태 코드만으로는 이 함정이 안 잡힌다).
        String statusAfterFirst = jdbcTemplate.queryForObject("SELECT status FROM change_request WHERE id = ?",
                String.class, s.approvalId);
        assertThat(statusAfterFirst).as("승인 결정이 실제로 커밋돼야 한다").isEqualTo("approved");

        결정_요청(s.academyId, s.approvalId, "{\"approve\":false,\"reject_reason\":\"아무 사유\"}")
                .andExpect(status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.error.code").value("APPROVAL_ALREADY_DECIDED"));
    }

    /** 확정 노선 위에 승인 대기 1건을 올린 최소 시나리오(학생1·3 은 유지, 학생2 가 취소 대상). */
    private 결정_시나리오 결정_시나리오를_만든다() {
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

        ChangeRequest changeRequest = ChangeRequest.forRequest(academyId, runId, 학생2,
                ChangeRequestSource.CHANGE_REQUEST, ChangeRequestType.CANCEL, (short) 2, 학생2, OffsetDateTime.now());
        long approvalId = changeRequestRepository.save(changeRequest).getId();

        return new 결정_시나리오(academyId, runId, approvalId, 학생2);
    }

    /** 결정 시나리오 픽스처 값 묶음. */
    private static final class 결정_시나리오 {
        final long academyId;
        final long runId;
        final long approvalId;
        final long studentId;

        결정_시나리오(long academyId, long runId, long approvalId, long studentId) {
            this.academyId = academyId;
            this.runId = runId;
            this.approvalId = approvalId;
            this.studentId = studentId;
        }
    }

    private String 미리보기_토큰_조회(long academyId, long approvalId) throws Exception {
        String body = mockMvc
                .perform(get("/api/v1/staff/approvals/" + approvalId).header("Authorization", 관계자_토큰(academyId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$.data.preview_token");
    }

    private org.springframework.test.web.servlet.ResultActions 결정_요청(long academyId, long approvalId, String body)
            throws Exception {
        return mockMvc.perform(post("/api/v1/staff/approvals/" + approvalId + "/decide")
                .header("Authorization", 관계자_토큰(academyId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String 관계자_토큰(long academyId) {
        return "Bearer " + tokenProvider.createAccessToken(academyId * 1000 + 1, academyId, Role.STAFF,
                AccountStatus.ACTIVE);
    }
}
