package src.backend.request.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.annotation.Import;

import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Role;
import src.backend.global.common.enums.Weekday;
import src.backend.global.policy.PolicyConstants;
import src.backend.global.security.JwtTokenProvider;
import src.backend.request.entity.ChangeRequest;
import src.backend.request.entity.ChangeRequestSource;
import src.backend.request.entity.ChangeRequestType;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.command.RunConfirmationFixtures;
import src.backend.run.command.RunConfirmationService;
import src.backend.run.entity.RunCancelSource;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;
import testsupport.clock.FixedClock20300401Config;

/**
 * 학부모 앱의 일일 변경 신청(P-06, API_SPEC §3.8·§3.9) — Phase 8 목표 9(③구간 전 타입 차단) ·
 * 5(②구간 회차당 1회 한도, 신청 경로) · 2(②구간 approval_requested 알림, 신청 경로).
 *
 * <p>시각은 {@link FixedClockConfig} 로 고정한다({@code RunConfirmationServiceTest} 와 같은
 * 패턴) — 회차의 {@code confirm_at}·{@code depart_time} 을 이 고정 시각 기준 상대값으로 둬야
 * ①②③ 구간을 결정론적으로 밟는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(FixedClock20300401Config.class)
class ChangeRequestControllerTest {

    private static final String CHANGE_REQUESTS = "/api/v1/students/%d/change-requests";

    private static final LocalDate SERVICE_DATE = LocalDate.of(2030, 4, 1); // 월요일

    private static final Weekday WEEKDAY = Weekday.MON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private Clock clock;

    @Autowired
    private RunConfirmationService confirmationService;

    @Autowired
    private ChangeRequestRepository changeRequestRepository;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private BusRepository busRepository;

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
    private RunRepository runRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private GuardianRepository guardianRepository;

    @Autowired
    private GuardianStudentRepository guardianStudentRepository;

    @Autowired
    private AcademyStaffRepository academyStaffRepository;

    /** 변경 감지가 만든 UPDATE 는 커밋 시점에야 나간다 — {@link JdbcTemplate} 로 읽기 전에 밀어낸다. */
    @PersistenceContext
    private EntityManager entityManager;

    private RunConfirmationFixtures fixtures;

    private ChangeRequestFixtures changeRequestFixtures;


    private RunConfirmationFixtures fixtures() {
        if (fixtures == null) {
            fixtures = new RunConfirmationFixtures(academyRepository, busRepository, routeRepository,
                    routeStopRepository, stopRepository, studentRepository, weeklyAddressRepository, runRepository);
        }
        return fixtures;
    }

    private ChangeRequestFixtures changeRequestFixtures() {
        if (changeRequestFixtures == null) {
            changeRequestFixtures = new ChangeRequestFixtures(accountRepository, guardianRepository,
                    guardianStudentRepository, academyStaffRepository);
        }
        return changeRequestFixtures;
    }

    // ── 목표 1 — ①구간 즉시 반영 + 재최적화 미호출 + 다음 확정 배치에 반영 ──────────────

    @Test
    @DisplayName("목표1 — ①구간 relocate 는 즉시 승인되고, 재최적화를 부르지 않으며, 다음 확정 배치에 반영된다")
    void 즉시구간_경유지_이동은_바로_승인되고_다음_확정에_반영된다() throws Exception {
        long academyId = fixtures().academyWithCoordinates();
        long busId = fixtures().bus(academyId);
        long originalStop = fixtures().stop(academyId, "37.560000", "126.970000");
        fixtures().route(academyId, busId, WEEKDAY, Direction.TO_ACADEMY, originalStop);

        long studentId = fixtures().student(academyId, "학생1");
        fixtures().verifiedAddress(studentId, originalStop, WEEKDAY, Direction.TO_ACADEMY, "37.560000", "126.970000");
        long accountId = changeRequestFixtures().parentLinkedTo(academyId, studentId);

        // 출발 31분 전 — confirm_at(출발-30분)이 아직 안 왔다(①구간).
        OffsetDateTime departTime = OffsetDateTime.now(clock).plusMinutes(31);
        long runId = fixtures().idleRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));

        MvcResult result = 신청_요청(accountId, academyId, studentId, "relocate", runId,
                "서울시 이사한동네 300", "이사")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("approved"))
                .andExpect(jsonPath("$.data.result").value("applied"))
                .andReturn();
        entityManager.flush();
        assertThat(본문(result)).isNotBlank();

        // 재최적화 미호출 — 회차는 여전히 idle, confirmed_route 산출물이 아직 없다.
        assertThat(runRepository.findById(runId).orElseThrow().getStatus().name()).isEqualTo("IDLE");
        Integer confirmedRouteCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM confirmed_route WHERE run_id = ?", Integer.class, runId);
        assertThat(confirmedRouteCount).as("①구간은 재최적화를 부르지 않는다(Ruling 198)").isZero();

        Long newStopId = jdbcTemplate.queryForObject(
                "SELECT new_stop_id FROM change_request WHERE run_id = ? AND student_id = ?", Long.class, runId,
                studentId);
        assertThat(newStopId).as("경유지 이동 목표가 즉시 저장돼야 한다").isNotEqualTo(originalStop);

        // 다음 확정 배치 1틱 — 바뀐 승하차지로 확정돼야 한다.
        confirmationService.confirmOne(runId);
        entityManager.flush();

        Long riderStopId = jdbcTemplate.queryForObject(
                "SELECT stop_id FROM run_rider WHERE run_id = ? AND student_id = ?", Long.class, runId, studentId);
        assertThat(riderStopId).as("승인된 일일 변경이 요일별 주소를 이겨야 한다").isEqualTo(newStopId);

        // run_rider(배정 기록)만 보면 노선 계산 자체는 옛 승하차지로 돌았는데 명단만 새 값으로 보이는
        // 상태를 놓친다 — 실제로 버스가 도는 경로(run_stop)에도 바뀐 승하차지가 실려야 한다.
        Integer routedStopCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM run_stop rs JOIN confirmed_route cr "
                        + "ON cr.current_version_id = rs.route_version_id "
                        + "WHERE cr.run_id = ? AND rs.stop_id = ?", Integer.class, runId, newStopId);
        assertThat(routedStopCount).as("노선 계산 산출물(run_stop)에도 바뀐 승하차지가 실려야 한다").isEqualTo(1);
    }

    // ── 목표 2 — ②구간 승인 대기 + 관계자 approval_requested 적재 ──────────────────────

    @Test
    @DisplayName("목표2 — ②구간 신청은 승인 대기로 접수되고 관계자에게 approval_requested 가 적재된다")
    void 승인대기구간_신청은_대기_상태로_접수되고_관계자에게_알림이_적재된다() throws Exception {
        long academyId = fixtures().academyWithCoordinates();
        long busId = fixtures().bus(academyId);
        long stopId = fixtures().stop(academyId, "37.560000", "126.970000");
        fixtures().route(academyId, busId, WEEKDAY, Direction.TO_ACADEMY, stopId);

        long studentId = fixtures().student(academyId, "학생1");
        fixtures().verifiedAddress(studentId, stopId, WEEKDAY, Direction.TO_ACADEMY, "37.560000", "126.970000");
        long accountId = changeRequestFixtures().parentLinkedTo(academyId, studentId);
        changeRequestFixtures().staffOf(academyId);

        // 출발 20분 전 — confirm_at(출발-30분)을 이미 지났다(②구간).
        OffsetDateTime departTime = OffsetDateTime.now(clock).plusMinutes(20);
        long runId = fixtures().idleRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));

        신청_요청(accountId, academyId, studentId, "cancel", runId, null, "사정상 결석")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("pending"))
                .andExpect(jsonPath("$.data.result").value("pending_approval"))
                .andExpect(jsonPath("$.data.deadline_at").isNotEmpty());
        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT deadline_at FROM change_request WHERE run_id = ?",
                OffsetDateTime.class, runId).toInstant()).as("Ruling 870 — 마감 = 출발 시각 + 운행 시작 창")
                .isEqualTo(departTime.plus(PolicyConstants.START_WINDOW).toInstant());

        // dedup_key 에 박힌 run_id 로 이 신청이 만든 행인지 가린다(리스너의 DEDUP_KEY_FORMAT).
        Integer notified = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_log WHERE academy_id = ? AND type = 'approval_requested' "
                        + "AND dedup_key LIKE ?",
                Integer.class, academyId, "approval_requested:" + runId + ":%");
        assertThat(notified).as("관계자에게 승인 요청 알림이 적재돼야 한다").isEqualTo(1);

        // R13 — ApprovalRequestedEvent 는 studentId 를 들고 있는 단일 학생 이벤트다(docs/archive/rounds/be-rounds-r5-r14.md §8.16 목표 3).
        Long notifiedStudentId = jdbcTemplate.queryForObject(
                "SELECT student_id FROM notification_log WHERE academy_id = ? AND type = 'approval_requested' "
                        + "AND dedup_key LIKE ?",
                Long.class, academyId, "approval_requested:" + runId + ":%");
        assertThat(notifiedStudentId).as("student_id 가 채워진다").isEqualTo(studentId);
        String notifiedStudentName = jdbcTemplate.queryForObject(
                "SELECT student_name FROM notification_log WHERE academy_id = ? AND type = 'approval_requested' "
                        + "AND dedup_key LIKE ?",
                String.class, academyId, "approval_requested:" + runId + ":%");
        assertThat(notifiedStudentName).as("student_name 이 채워진다").isEqualTo("학생1");
    }

    /**
     * BR-084 — 같은 학원이라도 그 자녀가 대상이 아닌 회차(다른 차량의 노선)는 존재를 숨겨 {@code 404
     * RUN_NOT_FOUND} 다(§1.11). 대상 판정이 없으면 대상 밖 학생의 요청이 관계자 승인 대기에 올라간다.
     */
    @Test
    @DisplayName("BR-084 — 그 자녀가 대상이 아닌 같은 학원 회차를 지목하면 404 RUN_NOT_FOUND 이고 행이 생기지 않는다")
    void 대상이_아닌_회차를_지목하면_404이다() throws Exception {
        long academyId = fixtures().academyWithCoordinates();
        long busId = fixtures().bus(academyId);
        long otherBusId = fixtures().bus(academyId);
        long stopId = fixtures().stop(academyId, "37.560000", "126.970000");
        fixtures().route(academyId, busId, WEEKDAY, Direction.TO_ACADEMY, stopId);

        long studentId = fixtures().student(academyId, "학생1");
        fixtures().verifiedAddress(studentId, stopId, WEEKDAY, Direction.TO_ACADEMY, "37.560000", "126.970000");
        long accountId = changeRequestFixtures().parentLinkedTo(academyId, studentId);

        OffsetDateTime departTime = OffsetDateTime.now(clock).plusHours(3);
        long otherRunId = fixtures().idleRun(academyId, otherBusId, SERVICE_DATE, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));

        신청_요청(accountId, academyId, studentId, "cancel", otherRunId, null, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RUN_NOT_FOUND"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM change_request WHERE run_id = ?",
                Integer.class, otherRunId)).isZero();
    }

    // ── 목표 9 — ③구간은 타입을 가리지 않는다 ──────────────────────────────────────────

    @Test
    @DisplayName("목표9 — moving 회차는 type=cancel 도 403 CHANGE_WINDOW_CLOSED 다")
    void 운행중_회차는_탑승취소_신청도_거절된다() throws Exception {
        long academyId = fixtures().academyWithCoordinates();
        long busId = fixtures().bus(academyId);
        long stopId = fixtures().stop(academyId, "37.560000", "126.970000");
        fixtures().route(academyId, busId, WEEKDAY, Direction.TO_ACADEMY, stopId);

        long studentId = fixtures().student(academyId, "학생1");
        fixtures().verifiedAddress(studentId, stopId, WEEKDAY, Direction.TO_ACADEMY, "37.560000", "126.970000");
        long accountId = changeRequestFixtures().parentLinkedTo(academyId, studentId);

        OffsetDateTime departTime = OffsetDateTime.now(clock).plusHours(1);
        long runId = fixtures().idleRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        jdbcTemplate.update("UPDATE run SET status = 'moving' WHERE id = ?", runId);
        // 확정 이후 회차의 대상 판정은 명단으로 한다(BR-084) — 확정 배치가 만들었을 명단 행을 둔다.
        jdbcTemplate.update("INSERT INTO run_rider (run_id, student_id, stop_id, status, created_at, updated_at) "
                + "VALUES (?, ?, ?, 'waiting', now(), now())", runId, studentId, stopId);
        // JDBC 로 상태를 직접 바꾼 뒤 같은 트랜잭션에서 JPA 로 다시 읽으면, 이미 이 트랜잭션이
        // 관리 중인 Run 인스턴스를 1차 캐시가 그대로 돌려줘(신선한 SELECT 결과를 무시) status 가
        // 여전히 idle 로 보인다. clear() 로 영속성 컨텍스트를 비워 다음 조회가 DB 를 다시 읽게 한다.
        entityManager.clear();

        신청_요청(accountId, academyId, studentId, "cancel", runId, null, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CHANGE_WINDOW_CLOSED"));
    }

    @Test
    @DisplayName("목표9 — moving 회차는 type=relocate 도 403 CHANGE_WINDOW_CLOSED 다(전 타입 차단)")
    void 운행중_회차는_경유지_이동_신청도_거절된다() throws Exception {
        long academyId = fixtures().academyWithCoordinates();
        long busId = fixtures().bus(academyId);
        long stopId = fixtures().stop(academyId, "37.560000", "126.970000");
        fixtures().route(academyId, busId, WEEKDAY, Direction.TO_ACADEMY, stopId);

        long studentId = fixtures().student(academyId, "학생1");
        fixtures().verifiedAddress(studentId, stopId, WEEKDAY, Direction.TO_ACADEMY, "37.560000", "126.970000");
        long accountId = changeRequestFixtures().parentLinkedTo(academyId, studentId);

        OffsetDateTime departTime = OffsetDateTime.now(clock).plusHours(1);
        long runId = fixtures().idleRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        jdbcTemplate.update("UPDATE run SET status = 'moving' WHERE id = ?", runId);
        // 확정 이후 회차의 대상 판정은 명단으로 한다(BR-084) — 확정 배치가 만들었을 명단 행을 둔다.
        jdbcTemplate.update("INSERT INTO run_rider (run_id, student_id, stop_id, status, created_at, updated_at) "
                + "VALUES (?, ?, ?, 'waiting', now(), now())", runId, studentId, stopId);
        entityManager.clear(); // 위 테스트와 같은 이유 — 1차 캐시의 idle 스냅샷을 비운다.

        // ③구간 판정은 relocate 상세(주소 검증)를 보기 전에 이뤄진다 — new_address 없이도 막혀야 한다.
        신청_요청(accountId, academyId, studentId, "relocate", runId, null, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CHANGE_WINDOW_CLOSED"));
    }

    // ── 목표 5 — ②구간 회차당 1회 한도 ──────────────────────────────────────────────

    @Test
    @DisplayName("목표5 — ②구간 같은 회차 2번째 신청은 403 CHANGE_LIMIT_REACHED, 다른 회차는 201")
    void 같은_회차_두번째_신청은_한도로_막히고_다른_회차는_통과한다() throws Exception {
        long academyId = fixtures().academyWithCoordinates();
        long busId = fixtures().bus(academyId);
        long stopId = fixtures().stop(academyId, "37.560000", "126.970000");
        fixtures().route(academyId, busId, WEEKDAY, Direction.TO_ACADEMY, stopId);

        long studentId = fixtures().student(academyId, "학생1");
        fixtures().verifiedAddress(studentId, stopId, WEEKDAY, Direction.TO_ACADEMY, "37.560000", "126.970000");
        long accountId = changeRequestFixtures().parentLinkedTo(academyId, studentId);

        OffsetDateTime departTime = OffsetDateTime.now(clock).plusMinutes(20);
        // runB 는 uk_run_bus_date_direction_depart(bus_id·service_date·direction·depart_time UNIQUE)
        // 를 피하려고 출발 시각을 1분 늦춘다 — ②구간 판정에는 영향이 없다(여전히 now<departTime,
        // now>=confirmAt).
        long runA = fixtures().idleRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        OffsetDateTime departTimeB = departTime.plusMinutes(1);
        long runB = fixtures().idleRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY, departTimeB,
                departTimeB.minusMinutes(30));

        신청_요청(accountId, academyId, studentId, "cancel", runA, null, "1차")
                .andExpect(status().isCreated());
        entityManager.flush();

        신청_요청(accountId, academyId, studentId, "cancel", runA, null, "2차")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CHANGE_LIMIT_REACHED"));
        entityManager.flush();

        신청_요청(accountId, academyId, studentId, "cancel", runB, null, "다른회차")
                .andExpect(status().isCreated());
    }

    // ── 목표 — 주소 검증 실패 시 전건 보류 ──────────────────────────────────────────

    @Test
    @DisplayName("주소 검증 실패는 422 를 내고 change_request 행을 남기지 않는다")
    void 주소_검증에_실패하면_행이_생기지_않는다() throws Exception {
        long academyId = fixtures().academyWithCoordinates();
        long busId = fixtures().bus(academyId);
        long stopId = fixtures().stop(academyId, "37.560000", "126.970000");
        fixtures().route(academyId, busId, WEEKDAY, Direction.TO_ACADEMY, stopId);

        long studentId = fixtures().student(academyId, "학생1");
        fixtures().verifiedAddress(studentId, stopId, WEEKDAY, Direction.TO_ACADEMY, "37.560000", "126.970000");
        long accountId = changeRequestFixtures().parentLinkedTo(academyId, studentId);

        OffsetDateTime departTime = OffsetDateTime.now(clock).plusMinutes(31);
        long runId = fixtures().idleRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));

        신청_요청(accountId, academyId, studentId, "relocate", runId, "번지가 없는 어딘가", null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("ADDRESS_VERIFICATION_FAILED"))
                .andExpect(jsonPath("$.error.details.failed_entries[0]").value("번지가 없는 어딘가"));
        entityManager.flush();

        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM change_request WHERE run_id = ? AND student_id = ?", Integer.class, runId,
                studentId);
        assertThat(rowCount).as("검증 실패는 저장을 보류해야 한다(전건 성사 아니면 전건 보류)").isZero();
    }

    // ── 목표 — 조회: 4상태 그대로 · 연결 부재 자녀 403 · 타 학원 403 ──────────────────

    @Test
    @DisplayName("조회 — 4상태가 그대로 실리고, 연결 부재 자녀·타 학원 자녀는 403 이다")
    void 조회는_4상태를_그대로_싣고_권한_밖_자녀는_거절한다() throws Exception {
        long academyId = fixtures().academyWithCoordinates();
        long busId = fixtures().bus(academyId);
        long stopId = fixtures().stop(academyId, "37.560000", "126.970000");
        fixtures().route(academyId, busId, WEEKDAY, Direction.TO_ACADEMY, stopId);

        long studentId = fixtures().student(academyId, "학생1");
        fixtures().verifiedAddress(studentId, stopId, WEEKDAY, Direction.TO_ACADEMY, "37.560000", "126.970000");
        long accountId = changeRequestFixtures().parentLinkedTo(academyId, studentId);

        // approved — ①구간 신청은 접수와 동시에 승인된다.
        OffsetDateTime immediateDepart = OffsetDateTime.now(clock).plusMinutes(31);
        long runApproved = fixtures().idleRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY, immediateDepart,
                immediateDepart.minusMinutes(30));
        신청_요청(accountId, academyId, studentId, "cancel", runApproved, null, null)
                .andExpect(status().isCreated());
        entityManager.flush();

        // pending — ②구간 신청은 대기로 남는다.
        OffsetDateTime pendingDepart = OffsetDateTime.now(clock).plusMinutes(20);
        long runPending = fixtures().idleRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY, pendingDepart,
                pendingDepart.minusMinutes(30));
        신청_요청(accountId, academyId, studentId, "cancel", runPending, null, null)
                .andExpect(status().isCreated());
        entityManager.flush();

        // rejected — ②구간 신청을 접수한 뒤 T5 소유 전이를 엔티티에 직접 걸어 상태만 만든다(조회 판정
        // 범위 밖 — 이 태스크는 그 전이를 만들지 않고 값만 재사용한다). 출발 시각은 runPending 과
        // 1분씩 벌려 uk_run_bus_date_direction_depart 충돌을 피한다.
        OffsetDateTime rejectedDepart = pendingDepart.plusMinutes(1);
        long runRejected = fixtures().idleRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY, rejectedDepart,
                rejectedDepart.minusMinutes(30));
        신청_요청(accountId, academyId, studentId, "cancel", runRejected, null, null)
                .andExpect(status().isCreated());
        entityManager.flush();
        ChangeRequest rejected = 그_회차의_신청을_찾는다(runRejected, studentId);
        rejected.reject(accountId, OffsetDateTime.now(clock), "일정 조정 불가");
        changeRequestRepository.save(rejected);

        // auto_rejected — 같은 방식으로 T6 소유 자동거절 전이를 값만 재사용한다.
        OffsetDateTime autoRejectedDepart = pendingDepart.plusMinutes(2);
        long runAutoRejected = fixtures().idleRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY,
                autoRejectedDepart, autoRejectedDepart.minusMinutes(30));
        신청_요청(accountId, academyId, studentId, "cancel", runAutoRejected, null, null)
                .andExpect(status().isCreated());
        entityManager.flush();
        ChangeRequest autoRejected = 그_회차의_신청을_찾는다(runAutoRejected, studentId);
        autoRejected.autoReject(OffsetDateTime.now(clock));
        changeRequestRepository.save(autoRejected);
        entityManager.flush();

        MvcResult result = mockMvc.perform(get(CHANGE_REQUESTS.formatted(studentId))
                        .header("Authorization", 토큰(accountId, academyId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(4))
                .andExpect(jsonPath("$.data.pending_count").value(1))
                .andReturn();
        List<String> statuses = JsonPath.read(본문(result), "$.data.items[*].status");
        assertThat(statuses).containsExactlyInAnyOrder("approved", "pending", "rejected", "auto_rejected");

        // 연결 부재 자녀 — 같은 학원 보호자지만 이 학생과 연결이 없다.
        long unlinkedAccountId = changeRequestFixtures().parentWithoutLink(academyId);
        mockMvc.perform(get(CHANGE_REQUESTS.formatted(studentId))
                        .header("Authorization", 토큰(unlinkedAccountId, academyId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        // 타 학원 — 다른 학원 소속 보호자가 이 학생을 조회한다.
        long otherAcademyId = fixtures().academyWithCoordinates();
        long otherAccountId = changeRequestFixtures().parentWithoutLink(otherAcademyId);
        mockMvc.perform(get(CHANGE_REQUESTS.formatted(studentId))
                        .header("Authorization", 토큰(otherAccountId, otherAcademyId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    /** BR-251 — 이력은 최근 100건까지만 싣고, {@code pending_count} 는 잘린 이력과 무관하게 전체 대기 건수다. */
    @Test
    void 신청_이력은_최근_100건까지만_싣고_대기_건수는_전체를_센다() throws Exception {
        long academyId = fixtures().academyWithCoordinates();
        long busId = fixtures().bus(academyId);
        long stop = fixtures().stop(academyId, "37.560000", "126.970000");
        fixtures().route(academyId, busId, WEEKDAY, Direction.TO_ACADEMY, stop);
        long studentId = fixtures().student(academyId, "학생1");
        fixtures().verifiedAddress(studentId, stop, WEEKDAY, Direction.TO_ACADEMY, "37.560000", "126.970000");
        long accountId = changeRequestFixtures().parentLinkedTo(academyId, studentId);
        OffsetDateTime departTime = OffsetDateTime.now(clock).plusMinutes(20);
        long runId = fixtures().idleRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        OffsetDateTime base = OffsetDateTime.now(clock).minusDays(1);
        for (int i = 0; i < 105; i++) {
            changeRequestRepository.save(ChangeRequest.forRequest(academyId, runId, studentId,
                    ChangeRequestSource.CHANGE_REQUEST, ChangeRequestType.CANCEL, (short) 2, accountId,
                    base.plusSeconds(i)));
        }
        entityManager.flush();

        mockMvc.perform(get(CHANGE_REQUESTS.formatted(studentId)).header("Authorization", 토큰(accountId, academyId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(100))
                .andExpect(jsonPath("$.data.pending_count").value(105));
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────────

    private ChangeRequest 그_회차의_신청을_찾는다(long runId, long studentId) {
        return changeRequestRepository.findByAcademyIdAndStudentIdOrderByRequestedAtDesc(
                        runRepository.findById(runId).orElseThrow().getAcademyId(), studentId, Pageable.unpaged())
                .stream()
                .filter(cr -> cr.getRunId().equals(runId))
                .findFirst()
                .orElseThrow();
    }

    /** BR-250 — ①구간에서 이미 승인된 탑승 취소를 다시 신청하면(재전송) 새 신청 행을 만들지 않고 앞의 것을 돌려준다. */
    @Test
    void 이미_취소된_학생의_취소_신청을_다시_보내도_행은_하나다() throws Exception {
        long academyId = fixtures().academyWithCoordinates();
        long busId = fixtures().bus(academyId);
        long stop = fixtures().stop(academyId, "37.560000", "126.970000");
        fixtures().route(academyId, busId, WEEKDAY, Direction.TO_ACADEMY, stop);
        long studentId = fixtures().student(academyId, "학생1");
        fixtures().verifiedAddress(studentId, stop, WEEKDAY, Direction.TO_ACADEMY, "37.560000", "126.970000");
        long accountId = changeRequestFixtures().parentLinkedTo(academyId, studentId);
        OffsetDateTime departTime = OffsetDateTime.now(clock).plusMinutes(31);
        long runId = fixtures().idleRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));

        String first = 본문(신청_요청(accountId, academyId, studentId, "cancel", runId, null, null)
                .andExpect(status().isCreated()).andReturn());
        String second = 본문(신청_요청(accountId, academyId, studentId, "cancel", runId, null, null)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.result").value("applied"))
                .andReturn());
        entityManager.flush();

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM change_request WHERE run_id = ? AND student_id = ? AND type = 'cancel'",
                Integer.class, runId, studentId);
        assertThat(rows).as("같은 취소를 재전송할 때마다 승인 행이 쌓이면 이력과 관계자 통지가 중복된다").isEqualTo(1);
        assertThat(com.jayway.jsonpath.JsonPath.<String>read(second, "$.data.change_request_id"))
                .isEqualTo(com.jayway.jsonpath.JsonPath.<String>read(first, "$.data.change_request_id"));
    }

    /** BR-224 (Ruling 376) — 임시 취소된 회차에는 변경 신청이 접수되지 않는다(한도도 소비하지 않는다). */
    @Test
    void 취소된_회차에_변경_신청하면_409_RUN_CANCELED_이다() throws Exception {
        long academyId = fixtures().academyWithCoordinates();
        long busId = fixtures().bus(academyId);
        long stop = fixtures().stop(academyId, "37.560000", "126.970000");
        fixtures().route(academyId, busId, WEEKDAY, Direction.TO_ACADEMY, stop);
        long studentId = fixtures().student(academyId, "학생1");
        fixtures().verifiedAddress(studentId, stop, WEEKDAY, Direction.TO_ACADEMY, "37.560000", "126.970000");
        long accountId = changeRequestFixtures().parentLinkedTo(academyId, studentId);
        OffsetDateTime departTime = OffsetDateTime.now(clock).plusMinutes(31);
        long runId = fixtures().idleRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY, departTime,
                departTime.minusMinutes(30));
        runRepository.findById(runId).orElseThrow().cancel(OffsetDateTime.now(), RunCancelSource.STAFF);

        신청_요청(accountId, academyId, studentId, "cancel", runId, null, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RUN_CANCELED"));
    }

    private ResultActions 신청_요청(long accountId, Long academyId, long studentId, String type, long runId,
            String newAddress, String reason) throws Exception {
        return mockMvc.perform(post(CHANGE_REQUESTS.formatted(studentId))
                .header("Authorization", 토큰(accountId, academyId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(신청_본문(type, runId, newAddress, reason)));
    }

    private static String 신청_본문(String type, long runId, String newAddress, String reason) {
        return """
                {"type":"%s","run_id":%d,"new_address":%s,"reason":%s}""".formatted(type, runId,
                문자열_또는_null(newAddress), 문자열_또는_null(reason));
    }

    private static String 문자열_또는_null(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private String 토큰(long accountId, Long academyId) {
        return "Bearer " + tokenProvider.createAccessToken(accountId, academyId, Role.PARENT, AccountStatus.ACTIVE);
    }

    private static String 본문(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
