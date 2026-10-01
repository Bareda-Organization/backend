package src.backend.notification.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManagerFactory;

import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.Direction;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.controller.DriverRunFixtures;
import src.backend.run.event.RunStartedEvent;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import testsupport.clock.FixedClock20300401Config;

/**
 * 운행 시작 알림 적재의 SQL 문장 수는 수신자 수와 무관하다(R46 T-6) — 건별 {@code saveAndFlush} 면 학부모 수만큼 INSERT 가 나가
 * 회차 행 잠금을 쥔 시간이 수신자 수에 비례한다(운행 시작 약 40~75건). 한 문장으로 묶은 뒤에는 학생이 2명이든 10명이든 같다.
 *
 * <p>문장 수는 트랜잭션 안에서 이벤트 발행 전·후의 Hibernate 통계 차이로 센다 — 커밋 뒤 비동기 발송이 만드는 문장은 세지 않는다.
 * {@code @Transactional} 을 쓰지 않는 이유는 실제 커밋 경로(아웃박스 적재)를 그대로 타기 위해서다.
 */
@SpringBootTest
@Import(FixedClock20300401Config.class)
class RunStartedNotificationStatementCountTest {

    /** 두 측정의 차이 허용치 — 다른 스레드(스케줄러)가 같은 통계에 얹는 문장 몇 건의 잡음이다. */
    private static final long NOISE_TOLERANCE = 2;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    private final List<Long> academyIds = new ArrayList<>();

    /** FK 순서: 알림(FK 없음) → 탑승자 → 보호자 연결 → 보호자 → 학생 → 회차 → 정류장 → 버스 → 관계자 → 계정 → 학원. */
    @AfterEach
    void 뒷정리한다() {
        for (Long academyId : academyIds) {
            jdbcTemplate.update("DELETE FROM notification_log WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM run_rider WHERE run_id IN (SELECT id FROM run WHERE academy_id = ?)",
                    academyId);
            jdbcTemplate.update("DELETE FROM guardian_student WHERE guardian_id IN "
                    + "(SELECT id FROM guardian WHERE academy_id = ?)", academyId);
            jdbcTemplate.update("DELETE FROM guardian WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM student WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM run WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM stop WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM bus WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM academy_staff WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM account WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM academy WHERE id = ?", academyId);
        }
        academyIds.clear();
    }

    @Test
    @DisplayName("T-6 — 운행 시작 알림 적재의 SQL 문장 수는 학생(수신자) 수와 무관하다")
    void 운행_시작_알림_적재의_문장_수는_수신자_수와_무관하다() {
        long few = 적재_문장_수(2);
        // 앞 측정의 커밋 뒤 비동기 발송이 다음 측정의 통계에 얹히지 않게 기다린다
        await().atMost(Duration.ofSeconds(10)).until(() -> 발송_대기_행_수() == 0);
        long many = 적재_문장_수(10);

        assertThat(many - few).as("학생 2명 %d문장 · 10명 %d문장 — 수신자가 늘면 문장이 늘면 안 된다", few, many)
                .isLessThanOrEqualTo(NOISE_TOLERANCE);
    }

    private int 발송_대기_행_수() {
        return academyIds.isEmpty() ? 0 : jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_log WHERE push_state = 'pending' AND academy_id = ANY(?)",
                Integer.class, (Object) academyIds.toArray(new Long[0]));
    }

    /** 학생 {@code students} 명(각자 보호자 1명)이 탄 회차의 운행 시작 이벤트를 발행해 그 트랜잭션 안의 문장 수를 센다. */
    private long 적재_문장_수(int students) {
        DriverRunFixtures fixtures = new DriverRunFixtures(academyRepository, busRepository, stopRepository,
                studentRepository, accountRepository, managerRepository, assignmentRepository, runRepository,
                confirmedRouteRepository, routeVersionRepository, runStopRepository, runRiderRepository,
                academyStaffRepository, guardianRepository, guardianStudentRepository, changeRequestRepository);
        long academyId = fixtures.academy();
        academyIds.add(academyId);
        long busId = fixtures.bus(academyId);
        OffsetDateTime now = OffsetDateTime.now(clock);
        long runId = fixtures.confirmedRun(academyId, busId, Direction.TO_ACADEMY, now, now.minusMinutes(30));
        long stopId = fixtures.stop(academyId, "37.5", "127.0");
        fixtures.staffAccount(academyId, "관계자");
        for (int i = 0; i < students; i++) {
            long studentId = fixtures.student(academyId, "학생" + i);
            fixtures.guardianOf(academyId, studentId, "보호자" + i, now);
            fixtures.rider(runId, studentId, stopId, RiderStatus.WAITING, now);
        }

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        long[] counted = new long[1];
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            long before = statistics.getPrepareStatementCount();
            eventPublisher.publishEvent(new RunStartedEvent(runId, academyId, now, 0));
            counted[0] = statistics.getPrepareStatementCount() - before;
        });
        return counted[0];
    }
}
