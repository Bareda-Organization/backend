package src.backend.request.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import src.backend.boarding.command.BoardingCommandFixtures;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.security.AuthUser;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.request.dto.BoardingIntentToggleRequest;
import src.backend.run.command.RunStartCommandService;
import src.backend.run.repository.RunRepository;
import src.backend.student.entity.Student;
import src.backend.student.repository.StudentRepository;
import testsupport.clock.FixedClock20300401Config;
import testsupport.concurrency.AcademyRowsCleaner;
import testsupport.concurrency.PgLockWait;
import testsupport.concurrency.Race;
import testsupport.concurrency.RepositoryReadHooks;

/**
 * ②구간(승인 필요)에 접수되는 학부모 요청이 운행 시작과 겹칠 때 — 시작이 대기 요청을 종결한 뒤에 요청이 커밋되면 움직이는 회차에 대기(pending)
 * 요청이 남는다. 요청 경로가 운행 시작과 같은 회차 행 잠금을 먼저 쥐면 둘이 줄을 서서, 요청이 먼저면 시작이 그 요청을 종결하고 시작이 먼저면 요청이
 * 마감 구간으로 판정된다.
 *
 * <p>요청이 탑승 의사 행을 읽은 지점(요청이 쓰기 직전)에서 멈추고, 그 사이 운행 시작을 돌린다. {@code @Transactional} 을 쓰지 않는다.
 */
@SpringBootTest
@Import({FixedClock20300401Config.class, RepositoryReadHooks.class})
class ParentRequestRunStartRaceTest {

    private static final String ACADEMY_NAME = "승하차처리시험학원";

    @Autowired
    private BoardingIntentCommandService boardingIntentCommandService;

    @Autowired
    private ChangeRequestStore changeRequestStore;

    @Autowired
    private RunStartCommandService runStartCommandService;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ManagerRepository managerRepository;

    @Autowired
    private AssignmentRepository assignmentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    private BoardingCommandFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = BoardingCommandFixtures.of(context, entityManager);
        AcademyRowsCleaner.delete(jdbcTemplate, ACADEMY_NAME);
    }

    @AfterEach
    void tearDown() {
        RepositoryReadHooks.clear();
        AcademyRowsCleaner.delete(jdbcTemplate, ACADEMY_NAME);
    }

    private record Stage(long academyId, long runId, long studentId, AuthUser parent, AuthUser driver) {
    }

    /** 출발 5분 전·확정 시각 25분 전의 confirmed 회차 — ②구간이면서 운행 시작 창 안이다. 학생은 그 회차의 대기 탑승자다. */
    private Stage approvalSegmentRun() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long academyId = fixtures.academy();
        long runId = fixtures.run(academyId, fixtures.bus(academyId), now.plusMinutes(5), now.minusMinutes(25));
        jdbcTemplate.update("UPDATE run SET status = 'confirmed', confirmed_at = ? WHERE id = ?", now.minusMinutes(25),
                runId);
        long driverAccountId = fixtures.assignedManager(managerRepository, assignmentRepository, academyId, runId,
                ManagerRole.DRIVER, now);
        long stopId = fixtures.stop(academyId, "37.500000", "127.000000");
        long studentId = fixtures.student(academyId, "요청경합학생");
        BoardingCommandFixtures.GuardianAccount guardian = fixtures.guardian(academyId, "요청경합보호자");
        fixtures.linkChild(guardian.guardianId(), studentId, now.minusDays(1));
        fixtures.runRider(runId, studentId, stopId);
        entityManager.clear();
        return new Stage(academyId, runId, studentId,
                new AuthUser(guardian.accountId(), academyId, Role.PARENT, AccountStatus.ACTIVE),
                new AuthUser(driverAccountId, academyId, Role.DRIVER, AccountStatus.ACTIVE));
    }

    /** 요청이 탑승 의사 행을 읽은 지점에서 한 번 멈춰 서고, 그 사이 운행 시작을 돌린다. */
    private void raceRequestWithStart(Stage stage, Runnable request) throws Exception {
        CountDownLatch intentRead = new CountDownLatch(1);
        AtomicBoolean fired = new AtomicBoolean();
        RepositoryReadHooks.afterRead("findLockedByRunIdAndStudentId", () -> {
            if (fired.compareAndSet(false, true)) {
                intentRead.countDown();
                상대가_잠금을_기다릴_때까지_세운다();
            }
        });

        List<Throwable> failures = Race.run(() -> {
            request.run();
            return null;
        }, () -> {
            Race.awaitReached(intentRead, "요청이 탑승 의사 행을 읽은 지점");
            runStartCommandService.start(stage.driver(), stage.runId());
            return null;
        });

        assertThat(failures).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM run WHERE id = ?", String.class, stage.runId()))
                .isEqualTo("moving");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM change_request WHERE run_id = ? AND status = 'pending'", Integer.class,
                stage.runId())).as("움직이는 회차에 대기 요청이 남으면 승인 화면에 영원히 걸린다").isZero();
    }

    @Test
    @DisplayName("②구간 탑승 의사 끄기 요청이 운행 시작과 겹쳐도 움직이는 회차에 대기 요청이 남지 않는다")
    void 탑승_의사_요청과_운행_시작이_겹쳐도_대기_요청이_남지_않는다() throws Exception {
        Stage stage = approvalSegmentRun();

        raceRequestWithStart(stage, () -> boardingIntentCommandService.toggle(stage.parent(), stage.studentId(),
                stage.runId(), new BoardingIntentToggleRequest(false)));
    }

    @Test
    @DisplayName("②구간 탑승 취소 신청이 운행 시작과 겹쳐도 움직이는 회차에 대기 요청이 남지 않는다")
    void 탑승_취소_신청과_운행_시작이_겹쳐도_대기_요청이_남지_않는다() throws Exception {
        Stage stage = approvalSegmentRun();
        Student student = studentRepository.findById(stage.studentId()).orElseThrow();

        raceRequestWithStart(stage, () -> changeRequestStore.submitCancel(student,
                runRepository.findById(stage.runId()).orElseThrow(), stage.parent().accountId(), null));
    }

    /** 상대가 DB 잠금에서 기다리는 것이 보일 때까지 세워 둔다 — 잠금이 없어 상대가 기다리지 않으면 상한 뒤 예외로 시험이 실패한다. */
    private void 상대가_잠금을_기다릴_때까지_세운다() {
        try {
            PgLockWait.awaitWaitersOr(jdbcTemplate, 1, () -> false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
