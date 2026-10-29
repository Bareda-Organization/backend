package src.backend.exception.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
import src.backend.boarding.command.BoardingCommandService;
import src.backend.boarding.dto.RiderRevertRequest;
import src.backend.boarding.dto.RiderStatusUpdateRequest;
import src.backend.exception.dto.NoShowContactRequest;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.security.AuthUser;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import testsupport.clock.FixedClock20300401Config;
import testsupport.concurrency.AcademyRowsCleaner;
import testsupport.concurrency.PgLockWait;
import testsupport.concurrency.Race;
import testsupport.concurrency.RepositoryReadHooks;

/**
 * 미승차 케이스를 읽고 쓰는 연락 시도·되돌리기가 에스컬레이션 갱신과 겹칠 때 — 케이스 행을 읽은 뒤 에스컬레이션이 끼어들면 전체 행을
 * 덮어쓰는 쪽이 {@code escalated_at} 을 지워, 알림은 나갔는데 기록은 없는 상태가 된다. 에스컬레이션이 반영됐다(true)면 기록도 남아 있어야 하고,
 * 반영되지 않았다면 기록도 없어야 한다.
 *
 * <p>{@code @Transactional} 을 쓰지 않는다 — 두 스레드가 서로의 커밋을 보지 못하는 경합 자체가 사라진다.
 */
@SpringBootTest
@Import({FixedClock20300401Config.class, RepositoryReadHooks.class})
class NoShowCaseEscalationRaceTest {

    private static final String ACADEMY_NAME = "승하차처리시험학원";

    @Autowired
    private BoardingCommandService boardingCommandService;

    @Autowired
    private NoShowContactCommandService noShowContactCommandService;

    @Autowired
    private NoShowEscalationPersistence noShowEscalationPersistence;

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

    private record Stage(long runId, long riderId, long caseId, AuthUser escort) {
    }

    /** 미승차 케이스가 대기 시간을 넘긴 상태(에스컬레이션 대상)의 이동 중 회차를 만든다. */
    private Stage stageWithDueCase() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long academyId = fixtures.academy();
        long runId = fixtures.movingRun(academyId, fixtures.bus(academyId), now.minusMinutes(10), now.minusMinutes(40));
        long escortAccountId = fixtures.assignedManager(managerRepository, assignmentRepository, academyId, runId,
                ManagerRole.ESCORT, now);
        long stopId = fixtures.stop(academyId, "37.500000", "127.000000");
        fixtures.confirmedRunStop(runId, stopId, now.minusMinutes(30));
        long riderId = fixtures.runRider(runId, fixtures.student(academyId, "대기학생"), stopId);
        fixtures.runRider(runId, fixtures.student(academyId, "남은학생"), stopId);
        AuthUser escort = new AuthUser(escortAccountId, academyId, Role.ESCORT, AccountStatus.ACTIVE);
        boardingCommandService.updateStatus(escort, runId, riderId,
                new RiderStatusUpdateRequest("no_show", "manual", UUID.randomUUID(), now));
        long caseId = jdbcTemplate.queryForObject("SELECT id FROM no_show_case WHERE run_rider_id = ?", Long.class,
                riderId);
        jdbcTemplate.update("UPDATE no_show_case SET expires_at = ? WHERE id = ?",
                Timestamp.from(now.minusMinutes(1).toInstant()), caseId);
        entityManager.clear();
        return new Stage(runId, riderId, caseId, escort);
    }

    /** 케이스 읽기가 끝난 지점에서 한 번 멈춰 서고, 그 사이 에스컬레이션을 돌린 결과(true=반영)를 돌려준다. */
    private boolean raceWithEscalation(Stage stage, Runnable writer) throws Exception {
        CountDownLatch caseRead = new CountDownLatch(1);
        AtomicBoolean fired = new AtomicBoolean();
        Runnable pauseAfterCaseRead = () -> {
            if (fired.compareAndSet(false, true)) {
                caseRead.countDown();
                상대가_잠금을_기다릴_때까지_세운다();
            }
        };
        RepositoryReadHooks.afterRead("findByRunRiderId", pauseAfterCaseRead);
        RepositoryReadHooks.afterRead("findLockedByRunRiderId", pauseAfterCaseRead);
        AtomicReference<Boolean> escalated = new AtomicReference<>();

        List<Throwable> failures = Race.run(() -> {
            writer.run();
            return null;
        }, () -> {
            Race.awaitReached(caseRead, "케이스를 읽은 지점");
            escalated.set(noShowEscalationPersistence.escalateOne(stage.caseId(), OffsetDateTime.now(clock)));
            return null;
        });

        assertThat(failures).isEmpty();
        return escalated.get();
    }

    private Object escalatedAt(long caseId) {
        return jdbcTemplate.queryForList("SELECT escalated_at FROM no_show_case WHERE id = ?", caseId).get(0)
                .get("escalated_at");
    }

    @Test
    @DisplayName("응답 도착 연락 시도가 케이스를 읽은 사이 에스컬레이션이 끼어들어도 escalated_at 은 반영 여부와 일치한다")
    void 연락_시도와_에스컬레이션이_겹쳐도_기록이_지워지지_않는다() throws Exception {
        Stage stage = stageWithDueCase();

        boolean escalated = raceWithEscalation(stage, () -> noShowContactCommandService.recordAttempt(stage.escort(),
                stage.runId(), stage.riderId(), new NoShowContactRequest("call", "answered", null, null)));

        assertThat(escalatedAt(stage.caseId()) != null)
                .as("에스컬레이션이 반영됐다면(%s) escalated_at 이 남아 있어야 하고, 아니면 비어 있어야 한다", escalated)
                .isEqualTo(escalated);
    }

    @Test
    @DisplayName("미승차 되돌리기가 케이스를 읽은 사이 에스컬레이션이 끼어들어도 escalated_at 은 반영 여부와 일치한다")
    void 되돌리기와_에스컬레이션이_겹쳐도_기록이_지워지지_않는다() throws Exception {
        Stage stage = stageWithDueCase();

        boolean escalated = raceWithEscalation(stage, () -> boardingCommandService.revert(stage.escort(),
                stage.runId(), stage.riderId(), new RiderRevertRequest("잘못 눌렀어요")));

        assertThat(escalatedAt(stage.caseId()) != null)
                .as("에스컬레이션이 반영됐다면(%s) escalated_at 이 남아 있어야 하고, 아니면 비어 있어야 한다", escalated)
                .isEqualTo(escalated);
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
