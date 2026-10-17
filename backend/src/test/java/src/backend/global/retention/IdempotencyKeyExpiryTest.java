package src.backend.global.retention;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;

import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.ManagerRole;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.controller.DriverRunFixtures;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

/**
 * Ruling 894 ⑥ — 종료 회차를 {@code 409} 로 거절하는 두 표({@code rider_status_history} · {@code no_show_contact})의 멱등 키
 * ({@code client_key})는 <b>회차 종료 24시간 뒤</b>에 비워지고 행은 남는다(API_SPEC §1.7). 종료 회차도 받는
 * {@code emergency_alert} · {@code exception_report} 의 키는 <b>비워지지 않는다</b>(늦은 재전송이 새 행을 만들지 않게). 24시간 경계 바로 안쪽(23시간 59분)은 남고 바로 바깥(24시간 1분)은 비워진다 — 한쪽만 보면 "전부 비움"
 * 이나 "하나도 안 비움" 이 통과한다.
 *
 * <p>{@code @Transactional} 시험이라 JPA 로 만든 회차를 {@link #flush} 로 내보낸 뒤 같은 연결의 {@code JdbcTemplate} 이 읽는다. 기준 시각은 고정
 * 값을 {@code expire} 에 직접 넘긴다(시계에 기대지 않는다).
 */
@SpringBootTest
@Transactional
class IdempotencyKeyExpiryTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2030-04-03T12:00:00+09:00");

    private static final OffsetDateTime CUTOFF = NOW.minus(RetentionPolicy.IDEMPOTENCY_KEY_RETENTION);

    @Autowired
    private IdempotencyKeyExpiry expiry;

    @Autowired
    private RetentionCleanupScheduler scheduler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private Clock clock;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private BusRepository busRepository;

    @Autowired
    private StopRepository stopRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ManagerRepository managerRepository;

    @Autowired
    private AssignmentRepository assignmentRepository;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private ConfirmedRouteRepository confirmedRouteRepository;

    @Autowired
    private RouteVersionRepository routeVersionRepository;

    @Autowired
    private RunStopRepository runStopRepository;

    @Autowired
    private RunRiderRepository runRiderRepository;

    @Autowired
    private AcademyStaffRepository academyStaffRepository;

    @Autowired
    private GuardianRepository guardianRepository;

    @Autowired
    private GuardianStudentRepository guardianStudentRepository;

    @Autowired
    private ChangeRequestRepository changeRequestRepository;

    private DriverRunFixtures fx;

    private long academyId;

    private long busId;

    private long stopId;

    private long managerAccountId;

    private int studentSeq;

    @BeforeEach
    void setUp() {
        // 프록시를 벗긴다 — @SchedulerLock(ShedLock)이 락을 잡고 풀 때 시험 트랜잭션을 커밋해, 심은 행(만료된 미승차 케이스 등)이
        // 되돌려지지 않고 남아 다른 시험(DomainEventCounterWiringTest)의 에스컬레이션 집계에 섞인다
        scheduler = AopTestUtils.getUltimateTargetObject(scheduler);
        fx = new DriverRunFixtures(academyRepository, busRepository, stopRepository, studentRepository,
                accountRepository, managerRepository, assignmentRepository, runRepository, confirmedRouteRepository,
                routeVersionRepository, runStopRepository, runRiderRepository, academyStaffRepository,
                guardianRepository, guardianStudentRepository, changeRequestRepository);
        academyId = fx.academy();
        busId = fx.bus(academyId);
        stopId = fx.stop(academyId, "37.500000", "127.000000");
    }

    /** 키를 가진 4개 테이블 행을 한 회차에 하나씩 심은 묶음. */
    private record Rows(long runId, long history, long emergency, long report, long contact) {
    }

    @Test
    @DisplayName("R56 ⑥ — 회차가 24시간 1분 전에 끝났으면 승하차 이력·연락 기록 키만 비워지고 행은 남으며 비상·예외 보고 키는 그대로다")
    void 종료_24시간_뒤의_키는_비워지고_행은_남는다() {
        Rows rows = 회차와_키를_심는다(NOW.minusHours(24).minusMinutes(1));

        expiry.expire(CUTOFF, RetentionPolicy.BATCH_SIZE);

        assertThat(키(("rider_status_history"), rows.history())).as("승하차 이력 키").isNull();
        assertThat(키("no_show_contact", rows.contact())).as("연락 기록 키").isNull();
        assertThat(키("emergency_alert", rows.emergency())).as("비상 알림 키는 비우지 않는다").isNotNull();
        assertThat(키("exception_report", rows.report())).as("예외 보고 키는 비우지 않는다").isNotNull();
        assertThat(행수("rider_status_history", rows.history())).as("무기한 보존 행은 남는다").isEqualTo(1);
        assertThat(행수("emergency_alert", rows.emergency())).isEqualTo(1);
        assertThat(행수("exception_report", rows.report())).isEqualTo(1);
        assertThat(행수("no_show_contact", rows.contact())).isEqualTo(1);
    }

    @Test
    @DisplayName("R56 ⑥ — 회차가 23시간 59분 전에 끝났으면 키가 그대로 남는다(24시간 하한)")
    void 종료_24시간_안의_키는_남는다() {
        Rows rows = 회차와_키를_심는다(NOW.minusHours(23).minusMinutes(59));

        expiry.expire(CUTOFF, RetentionPolicy.BATCH_SIZE);

        assertThat(키("rider_status_history", rows.history())).isNotNull();
        assertThat(키("emergency_alert", rows.emergency())).isNotNull();
        assertThat(키("exception_report", rows.report())).isNotNull();
        assertThat(키("no_show_contact", rows.contact())).isNotNull();
    }

    @Test
    @DisplayName("R56 ⑥ — 행을 만든 시각이 아니라 회차 종료 시각이 기준이다(오래전 만든 행도 회차가 24시간 안에 끝났으면 남는다)")
    void 기준은_행을_만든_시각이_아니라_회차_종료_시각이다() {
        Rows rows = 회차와_키를_심는다(NOW.minusHours(1));
        jdbcTemplate.update("UPDATE rider_status_history SET changed_at = ? WHERE id = ?", NOW.minusDays(3),
                rows.history());
        jdbcTemplate.update("UPDATE no_show_contact SET attempted_at = ? WHERE id = ?", NOW.minusDays(3),
                rows.contact());

        expiry.expire(CUTOFF, RetentionPolicy.BATCH_SIZE);

        assertThat(키("rider_status_history", rows.history())).as("방금 끝난 회차의 이력 키").isNotNull();
        assertThat(키("no_show_contact", rows.contact())).as("방금 끝난 회차의 연락 기록 키").isNotNull();
    }

    @Test
    @DisplayName("R56 ⑥ — 보존 정리 배치가 이 정리를 실제로 부른다(배선)")
    void 보존_정리_배치가_멱등_키를_비운다() {
        // 배치는 실제 시각을 쓴다 — 회차가 실제 now 기준 25시간 전에 끝난 것으로 심는다
        Rows rows = 회차와_키를_심는다(OffsetDateTime.now(clock).minusHours(25));

        scheduler.cleanUp();

        assertThat(키("rider_status_history", rows.history())).isNull();
        assertThat(키("no_show_contact", rows.contact())).isNull();
        assertThat(키("emergency_alert", rows.emergency())).as("배치를 거쳐도 비상 알림 키는 남는다").isNotNull();
        assertThat(키("exception_report", rows.report())).as("배치를 거쳐도 예외 보고 키는 남는다").isNotNull();
    }

    // ── 도우미 ────────────────────────────────────────────────────────────

    /** 종료 시각이 {@code finishedAt} 인 회차 1건과, 그 회차를 가리키며 키를 가진 4개 테이블 행을 하나씩 심는다. */
    private Rows 회차와_키를_심는다(OffsetDateTime finishedAt) {
        OffsetDateTime departTime = finishedAt.minusHours(1);
        long runId = fx.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30));
        managerAccountId = fx.assignedManager(academyId, runId, ManagerRole.ESCORT, "동승자" + studentSeq, departTime);
        fx.startRun(runId, departTime);
        fx.finishRun(runId, finishedAt);
        long studentId = fx.student(academyId, "학생" + (++studentSeq));
        long riderId = fx.rider(runId, studentId, stopId, RiderStatus.NO_SHOW, departTime);
        flush();

        long history = jdbcTemplate.queryForObject("""
                INSERT INTO rider_status_history (run_rider_id, to_status, changed_at, actor_type, client_key)
                VALUES (?, 'boarded', ?, 'escort', ?) RETURNING id""", Long.class, riderId, departTime, UUID.randomUUID());
        long emergency = jdbcTemplate.queryForObject("""
                INSERT INTO emergency_alert (academy_id, run_id, bus_no, raised_by, raised_by_role, type, rider_count,
                                             occurred_at, received_at, client_key)
                VALUES (?, ?, '1호', ?, 'escort', 'accident', 1, ?, ?, ?) RETURNING id""", Long.class, academyId, runId,
                managerAccountId, departTime, departTime, UUID.randomUUID());
        long report = jdbcTemplate.queryForObject("""
                INSERT INTO exception_report (academy_id, run_id, type, memo, reported_by, reported_at, client_key)
                VALUES (?, ?, 'road_block', '공사', ?, ?, ?) RETURNING id""", Long.class, academyId, runId,
                managerAccountId, departTime, UUID.randomUUID());
        long caseId = jdbcTemplate.queryForObject("""
                INSERT INTO no_show_case (run_rider_id, started_at, expires_at) VALUES (?, ?, ?) RETURNING id""",
                Long.class, riderId, departTime, departTime.plusMinutes(3));
        long contact = jdbcTemplate.queryForObject("""
                INSERT INTO no_show_contact (no_show_case_id, attempt_type, result, attempted_at, attempted_by, client_key)
                VALUES (?, 'call', 'no_answer', ?, ?, ?) RETURNING id""", Long.class, caseId, departTime,
                managerAccountId, UUID.randomUUID());
        return new Rows(runId, history, emergency, report, contact);
    }

    private void flush() {
        entityManager.flush();
        entityManager.clear();
    }

    private Object 키(String table, long id) {
        return jdbcTemplate.queryForObject("SELECT client_key FROM " + table + " WHERE id = ?", Object.class, id);
    }

    private Integer 행수(String table, long id) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table + " WHERE id = ?", Integer.class, id);
    }
}
