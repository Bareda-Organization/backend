package src.backend.global.retention;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.OffsetDateTime;

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
 * R56 Ruling 894 ⑦ — 지연 알림 발신 기록({@code delay_notice})은 {@code notification_log} 와 <b>같은 보존 주기</b>(14일, 같은 상수)로 지워진다.
 * 경계 바로 안쪽(13일)은 남고 바로 바깥(15일)은 지워진다.
 *
 * <p>{@code @Transactional} 시험이라 배치의 삭제가 같은 연결에서 일어나 시험이 끝나면 되돌려진다. 배치는 실제 시각을 쓰므로 심는 행은 실제
 * {@code now} 기준 상대 시각이다({@code RetentionCleanupSchedulerTest} 와 같은 근거).
 */
@SpringBootTest
@Transactional
class DelayNoticeRetentionTest {

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

    private long runId;

    private long accountId;

    @BeforeEach
    void setUp() {
        // 프록시를 벗긴다 — @SchedulerLock(ShedLock)이 락을 잡고 풀 때 시험 트랜잭션을 커밋해, 심은 행(만료된 미승차 케이스 등)이
        // 되돌려지지 않고 남아 다른 시험(DomainEventCounterWiringTest)의 에스컬레이션 집계에 섞인다
        scheduler = AopTestUtils.getUltimateTargetObject(scheduler);
        DriverRunFixtures fx = new DriverRunFixtures(academyRepository, busRepository, stopRepository,
                studentRepository, accountRepository, managerRepository, assignmentRepository, runRepository,
                confirmedRouteRepository, routeVersionRepository, runStopRepository, runRiderRepository,
                academyStaffRepository, guardianRepository, guardianStudentRepository, changeRequestRepository);
        long academyId = fx.academy();
        long busId = fx.bus(academyId);
        OffsetDateTime departTime = OffsetDateTime.now(clock).plusHours(2);
        runId = fx.confirmedRun(academyId, busId, Direction.TO_ACADEMY, departTime, departTime.minusMinutes(30));
        accountId = fx.assignedManager(academyId, runId, ManagerRole.ESCORT, "동승자", departTime);
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("R56 ⑦ — delay_notice 는 14일 초과 행만 지워지고 기한 안 행은 남는다 (notification_log 와 같은 주기)")
    void 지연_알림_발신_기록은_14일_초과_행만_지워진다() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long inWindow = 발신_기록(now.minusDays(13));
        long outOfWindow = 발신_기록(now.minusDays(15));

        scheduler.cleanUp();

        assertThat(존재한다(inWindow)).as("13일 전 발신 기록은 남는다").isTrue();
        assertThat(존재한다(outOfWindow)).as("15일 전 발신 기록은 지워진다").isFalse();
    }

    @Test
    @DisplayName("R56 ⑦ — delay_notice 의 보존 주기는 notification_log 의 상수와 같은 값을 읽는다")
    void 보존_주기는_알림_로그의_상수를_그대로_읽는다() {
        assertThat(RetentionPolicy.NOTIFICATION_LOG_RETENTION.toDays()).as("알림 로그 보존 일수").isEqualTo(14);
        OffsetDateTime now = OffsetDateTime.now(clock);
        // 알림 로그 컷오프 바로 안쪽·바깥 1분으로 심어 상수를 바꾸면 이 시험이 따라 움직임을 본다
        OffsetDateTime cutoff = new RetentionPolicy().notificationLogCutoff(now);
        long justInside = 발신_기록(cutoff.plusMinutes(1));
        long justOutside = 발신_기록(cutoff.minusMinutes(1));

        scheduler.cleanUp();

        assertThat(존재한다(justInside)).isTrue();
        assertThat(존재한다(justOutside)).isFalse();
    }

    private long 발신_기록(OffsetDateTime sentAt) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO delay_notice (run_id, sent_by_account_id, minutes, reason, sent_at)
                VALUES (?, ?, 10, 'traffic', ?) RETURNING id""", Long.class, runId, accountId, sentAt);
    }

    private boolean 존재한다(long id) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM delay_notice WHERE id = ?", Integer.class, id) > 0;
    }
}
