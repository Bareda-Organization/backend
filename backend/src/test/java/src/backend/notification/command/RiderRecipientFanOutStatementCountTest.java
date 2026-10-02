package src.backend.notification.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManagerFactory;

import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.location.event.RunApproachingStopEvent;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.request.command.BoardingIntentFixtures;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.event.RunAutoAlightedEvent;
import src.backend.run.event.StopDepartedEvent;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * 승하차(정차지 출발)·정차지 접근·자동 하차 알림 적재의 SQL 문장 수는 보호자 수와 무관하다(BR-374) — 지연 신고·비상 접수만 묶고
 * ({@code RecipientFanOutStatementCountTest}, BR-354) 이 학부모 갈래를 건별 {@code append} 로 두면, 정차지 하나를 출발할 때 탑승자 수 ×
 * 보호자 수만큼, 접근·자동 하차는 보호자 수만큼 INSERT 가 나간다.
 *
 * <p>학원 관계자 갈래({@code appendToStaff} · 예외 보고 · 미승차 에스컬레이션 · 의사 변경 · 운행 종료)는 이 시험에 없다 — 학원당 재직 관계자가
 * 1명({@code uk_academy_staff_academy_active})이라 수신자가 늘어나지 않고, 묶어도 문장 수가 같아 이 시험이 실패할 수 없다.
 *
 * <p>문장 수는 트랜잭션 안에서 이벤트 발행 전·후의 Hibernate 통계 차이로 센다(커밋 뒤 비동기 발송의 문장은 세지 않는다). 수신자는 2명과
 * 10명 두 번 재서 차이를 본다.
 */
@SpringBootTest
class RiderRecipientFanOutStatementCountTest {

    /** 두 측정의 차이 허용치 — 다른 스레드(스케줄러)가 같은 통계에 얹는 문장 몇 건의 잡음이다. */
    private static final long NOISE_TOLERANCE = 2;

    private static final OffsetDateTime AT = OffsetDateTime.of(2030, 4, 1, 9, 0, 0, 0, ZoneOffset.ofHours(9));

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private RunRiderRepository runRiderRepository;

    private final List<Long> academyIds = new ArrayList<>();

    @AfterEach
    void 뒷정리한다() {
        for (Long academyId : academyIds) {
            jdbcTemplate.update("DELETE FROM notification_log WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM run WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM guardian_student WHERE student_id IN "
                    + "(SELECT id FROM student WHERE academy_id = ?)", academyId);
            jdbcTemplate.update("DELETE FROM student WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM guardian WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM account WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM stop WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM bus WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM academy WHERE id = ?", academyId);
        }
        academyIds.clear();
    }

    @Test
    @DisplayName("BR-374 — 정차지 출발 알림 적재의 SQL 문장 수는 탑승자 수(보호자 수)와 무관하다")
    void 정차지_출발_적재의_문장_수는_탑승자_수와_무관하다() {
        assertStatementCountIndependentOfRecipients(riders -> {
            BoardingIntentFixtures fixtures = fixtures();
            long academyId = academy(fixtures);
            long runId = fixtures.run(academyId, fixtures.bus(academyId), AT, AT.minusMinutes(30));
            long stopId = fixtures.stop(academyId, "37.500000", "127.000000");
            for (int i = 0; i < riders; i++) {
                long studentId = studentWithGuardians(fixtures, academyId, 1);
                long riderId = runRiderRepository.save(RunRider.uponConfirmation(runId, studentId, stopId)).getId();
                jdbcTemplate.update("UPDATE run_rider SET status = 'boarded' WHERE id = ?", riderId);
            }
            return new StopDepartedEvent(runId, academyId, stopId, AT);
        });
    }

    @Test
    @DisplayName("BR-374 — 정차지 접근 알림 적재의 SQL 문장 수는 보호자 수와 무관하다")
    void 정차지_접근_적재의_문장_수는_보호자_수와_무관하다() {
        assertStatementCountIndependentOfRecipients(guardians -> {
            BoardingIntentFixtures fixtures = fixtures();
            long academyId = academy(fixtures);
            long runId = fixtures.run(academyId, fixtures.bus(academyId), AT, AT.minusMinutes(30));
            long stopId = fixtures.stop(academyId, "37.510000", "127.010000");
            return new RunApproachingStopEvent(runId, academyId, studentWithGuardians(fixtures, academyId, guardians),
                    stopId, AT);
        });
    }

    @Test
    @DisplayName("BR-374 — 자동 하차 알림 적재의 SQL 문장 수는 보호자 수와 무관하다")
    void 자동_하차_적재의_문장_수는_보호자_수와_무관하다() {
        assertStatementCountIndependentOfRecipients(guardians -> {
            BoardingIntentFixtures fixtures = fixtures();
            long academyId = academy(fixtures);
            long runId = fixtures.run(academyId, fixtures.bus(academyId), AT, AT.minusMinutes(30));
            return new RunAutoAlightedEvent(runId, academyId, studentWithGuardians(fixtures, academyId, guardians),
                    AT);
        });
    }

    /** 수신자 2명·10명으로 이벤트를 만들어 각각 발행하고, 앞 측정의 비동기 발송이 끝난 뒤 뒤 측정을 한다. */
    private void assertStatementCountIndependentOfRecipients(IntFunction<Object> eventWithRecipients) {
        long few = statementsWhilePublishing(eventWithRecipients.apply(2));
        await().atMost(Duration.ofSeconds(10)).until(() -> pendingRows() == 0);
        long many = statementsWhilePublishing(eventWithRecipients.apply(10));

        assertThat(many - few).as("수신자 2명 %d문장 · 10명 %d문장 — 수신자가 늘면 문장이 늘면 안 된다", few, many)
                .isLessThanOrEqualTo(NOISE_TOLERANCE);
    }

    private long statementsWhilePublishing(Object event) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        long[] counted = new long[1];
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            long before = statistics.getPrepareStatementCount();
            eventPublisher.publishEvent(event);
            counted[0] = statistics.getPrepareStatementCount() - before;
        });
        return counted[0];
    }

    private int pendingRows() {
        return academyIds.isEmpty() ? 0 : jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_log WHERE push_state = 'pending' AND academy_id = ANY(?)",
                Integer.class, (Object) academyIds.toArray(new Long[0]));
    }

    private long academy(BoardingIntentFixtures fixtures) {
        long academyId = fixtures.academy();
        academyIds.add(academyId);
        return academyId;
    }

    /** 보호자 {@code guardians} 명이 연결된 학생 1명 — 알림 수신자는 이 연결에서 나온다. */
    private long studentWithGuardians(BoardingIntentFixtures fixtures, long academyId, int guardians) {
        long studentId = fixtures.student(academyId, "수신자시험학생");
        for (int i = 0; i < guardians; i++) {
            fixtures.linkChild(fixtures.guardian(academyId, "보호자" + i).guardianId(), studentId, AT.minusDays(1));
        }
        return studentId;
    }

    private BoardingIntentFixtures fixtures() {
        return new BoardingIntentFixtures(context.getBean(AcademyRepository.class),
                context.getBean(BusRepository.class), context.getBean(StudentRepository.class),
                context.getBean(GuardianRepository.class), context.getBean(GuardianStudentRepository.class),
                context.getBean(AccountRepository.class), context.getBean(AcademyStaffRepository.class),
                context.getBean(RunRepository.class), context.getBean(StopRepository.class),
                context.getBean(ConfirmedRouteRepository.class), context.getBean(RouteVersionRepository.class),
                context.getBean(RunStopRepository.class), runRiderRepository, context.getBean(ManagerRepository.class),
                context.getBean(AssignmentRepository.class), context.getBean(RouteRepository.class),
                context.getBean(RouteStopRepository.class), context.getBean(WeeklyAddressRepository.class));
    }
}
