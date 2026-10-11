package src.backend.boarding.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.dto.RiderRevertRequest;
import src.backend.boarding.dto.RiderStatusUpdateRequest;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.routing.entity.RunStop;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.command.RunArrivalCommandService;
import src.backend.run.command.StopDepartureService;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import testsupport.clock.FixedClock20300401Config;
import testsupport.concurrency.RepositoryReadHooks;

/**
 * 승하차 기록(PATCH)·되돌리기가 기사의 도착 처리 · 근접 출발 판정과 겹칠 때의 <b>잠금 순서</b> — R53 게이트 I1(하원 회차 교착)의 재현본이다.
 * 승하차지 행을 쥔 PATCH 가 회차 행을 기다리는 동안 도착 처리가 회차 행을 쥔 채 그 승하차지 행을 기다리면 서로를 기다리는 교착
 * ({@code deadlock detected})이 되어 한쪽이 500 이 됐다. 전 경로가 회차 행을 먼저 쥐는 순서({@code RunRowLock})를 지키면 두 요청은 줄을 선다.
 *
 * <p>겹침은 PATCH 가 승하차지 행 잠금을 쥔 순간에 멈추고, 도착 처리가 회차 행을 쥔 순간에 풀어 주는 두 지점으로 강제한다 — 고쳐진
 * 순서에서는 도착 처리가 회차 행에서 먼저 막혀 그 지점에 오지 못하므로 PATCH 가 시간 만료로 풀려 이어 간다.
 *
 * <p>{@code @Transactional} 을 쓰지 않는다 — 두 스레드가 서로의 커밋을 보지 못하는 경합 자체가 사라진다.
 */
@SpringBootTest
@Import({FixedClock20300401Config.class, RepositoryReadHooks.class})
class BoardingLockOrderConcurrencyTest {

    private static final long HOLD_LIMIT_SECONDS = 3;

    private static final long WAIT_LIMIT_SECONDS = 60;

    private static final String ACADEMY_NAME = "승하차처리시험학원";

    @Autowired
    private BoardingCommandService boardingCommandService;

    @Autowired
    private RunArrivalCommandService runArrivalCommandService;

    @Autowired
    private StopDepartureService stopDepartureService;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private BusRepository busRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private GuardianRepository guardianRepository;

    @Autowired
    private GuardianStudentRepository guardianStudentRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AcademyStaffRepository academyStaffRepository;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private StopRepository stopRepository;

    @Autowired
    private ConfirmedRouteRepository confirmedRouteRepository;

    @Autowired
    private RouteVersionRepository routeVersionRepository;

    @Autowired
    private RunStopRepository runStopRepository;

    @Autowired
    private RunRiderRepository runRiderRepository;

    @Autowired
    private ManagerRepository managerRepository;

    @Autowired
    private AssignmentRepository assignmentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private Clock clock;

    private BoardingCommandFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new BoardingCommandFixtures(academyRepository, busRepository, studentRepository,
                guardianRepository, guardianStudentRepository, accountRepository, academyStaffRepository,
                runRepository, stopRepository, runRiderRepository, confirmedRouteRepository, routeVersionRepository,
                runStopRepository, jdbcTemplate, entityManager);
        cleanUpMarkedRows();
    }

    @AfterEach
    void tearDown() {
        RepositoryReadHooks.clear();
        cleanUpMarkedRows();
    }

    private void cleanUpMarkedRows() {
        String academyIds = "(SELECT id FROM academy WHERE name = '" + ACADEMY_NAME + "')";
        String runIds = "(SELECT id FROM run WHERE academy_id IN " + academyIds + ")";
        String riderIds = "(SELECT id FROM run_rider WHERE run_id IN " + runIds + ")";
        String accountIds = "(SELECT id FROM account WHERE academy_id IN " + academyIds + ")";
        jdbcTemplate.update("DELETE FROM notification_log WHERE recipient_account_id IN " + accountIds);
        jdbcTemplate.update("DELETE FROM no_show_contact WHERE no_show_case_id IN "
                + "(SELECT id FROM no_show_case WHERE run_rider_id IN " + riderIds + ")");
        jdbcTemplate.update("DELETE FROM no_show_case WHERE run_rider_id IN " + riderIds);
        jdbcTemplate.update("DELETE FROM rider_status_history WHERE run_rider_id IN " + riderIds);
        jdbcTemplate.update("DELETE FROM run_rider WHERE run_id IN " + runIds);
        jdbcTemplate.update("DELETE FROM assignment WHERE run_id IN " + runIds);
        jdbcTemplate.update("UPDATE confirmed_route SET current_version_id = NULL WHERE run_id IN " + runIds);
        jdbcTemplate.update("DELETE FROM run_stop WHERE route_version_id IN "
                + "(SELECT id FROM route_version WHERE confirmed_route_id IN " + runIds + ")");
        jdbcTemplate.update("DELETE FROM route_version WHERE confirmed_route_id IN " + runIds);
        jdbcTemplate.update("DELETE FROM confirmed_route WHERE run_id IN " + runIds);
        jdbcTemplate.update("DELETE FROM run WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM manager WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM academy_setting WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM academy_staff WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM guardian_student WHERE student_id IN "
                + "(SELECT id FROM student WHERE academy_id IN " + academyIds + ")");
        jdbcTemplate.update("DELETE FROM guardian WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM student WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM account WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM stop WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM bus WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM academy WHERE name = '" + ACADEMY_NAME + "'");
    }

    /** 이동 중인 회차 한 건과 그 확정 노선의 승하차지 하나, 동승자·기사 배치. */
    private record Stage(long academyId, long runId, long stopId, long runStopId, AuthUser escort, AuthUser driver) {
    }

    private Stage stage(String direction) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long academyId = fixtures.academy();
        long runId = fixtures.movingRun(academyId, fixtures.bus(academyId), now.minusMinutes(10), now.minusMinutes(40));
        jdbcTemplate.update("UPDATE run SET direction = ? WHERE id = ?", direction, runId);
        long escortAccountId = fixtures.assignedManager(managerRepository, assignmentRepository, academyId, runId,
                ManagerRole.ESCORT, now);
        long driverAccountId = fixtures.assignedManager(managerRepository, assignmentRepository, academyId, runId,
                ManagerRole.DRIVER, now);
        long stopId = fixtures.stop(academyId, "37.500000", "127.000000");
        long runStopId = fixtures.confirmedRunStop(runId, stopId, now.minusMinutes(30));
        entityManager.clear();
        return new Stage(academyId, runId, stopId, runStopId,
                new AuthUser(escortAccountId, academyId, Role.ESCORT, AccountStatus.ACTIVE),
                new AuthUser(driverAccountId, academyId, Role.DRIVER, AccountStatus.ACTIVE));
    }

    private long rider(Stage stage, String name, String status) {
        long riderId = fixtures.runRider(stage.runId(), fixtures.student(stage.academyId(), name), stage.stopId());
        jdbcTemplate.update("UPDATE run_rider SET status = ? WHERE id = ?", status, riderId);
        entityManager.clear();
        return riderId;
    }

    private void record(Stage stage, long riderId, String status) {
        boardingCommandService.updateStatus(stage.escort(), stage.runId(), riderId,
                new RiderStatusUpdateRequest(status, "manual", UUID.randomUUID(), OffsetDateTime.now(clock)));
    }

    private void arrive(Stage stage) {
        runArrivalCommandService.arrive(stage.driver(), stage.runId(), stage.runStopId());
    }

    private void depart(Stage stage) {
        RunStop runStop = runStopRepository.findById(stage.runStopId()).orElseThrow();
        stopDepartureService.claimAndPublish(runStop, stage.runId(), stage.academyId(), OffsetDateTime.now(clock));
    }

    /**
     * 두 호출을 동시에 띄우고 던진 예외를 모아 돌려준다 — 교착이면 한쪽이 {@code deadlock detected} 로 던진다. 예외 없이 끝나야 빈 목록이다.
     */
    private List<Throwable> runConcurrently(Callable<?> first, Callable<?> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Throwable> failures = new ArrayList<>();
        try {
            List<Future<?>> futures = List.of(pool.submit(first), pool.submit(second));
            for (Future<?> future : futures) {
                try {
                    future.get(WAIT_LIMIT_SECONDS, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    failures.add(e.getCause());
                }
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(WAIT_LIMIT_SECONDS, TimeUnit.SECONDS);
        }
        return failures;
    }

    /** PATCH 가 승하차지 행 잠금을 쥔 순간에 멈추고, 도착 처리가 회차 행을 쥔 채 정차 목록을 읽는 순간 풀어 준다. */
    private CountDownLatch pausePatchWhileHoldingStopUntilArrivalHoldsRun() {
        CountDownLatch patchHoldsStop = new CountDownLatch(1);
        CountDownLatch arrivalHoldsRun = new CountDownLatch(1);
        AtomicBoolean fired = new AtomicBoolean();
        RepositoryReadHooks.afterRead("findLockedByRouteVersionIdAndStopId", () -> {
            if (fired.compareAndSet(false, true)) {
                patchHoldsStop.countDown();
                await(arrivalHoldsRun);
            }
        });
        RepositoryReadHooks.afterRead("findAllByRouteVersionIdAndAcademyIdOrderBySeq", arrivalHoldsRun::countDown);
        return patchHoldsStop;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(HOLD_LIMIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String runStatus(long runId) {
        return jdbcTemplate.queryForObject("SELECT status FROM run WHERE id = ?", String.class, runId);
    }

    private String riderStatus(long riderId) {
        return jdbcTemplate.queryForObject("SELECT status FROM run_rider WHERE id = ?", String.class, riderId);
    }

    @Test
    @DisplayName("I1 교착 — 하원 마지막 승하차지의 마지막 하차(PATCH)와 기사의 도착 처리가 겹쳐도 교착 없이 둘 다 끝나고 회차가 종료된다")
    void 마지막_하차와_도착_처리가_겹쳐도_교착하지_않는다() throws Exception {
        Stage stage = stage("from_academy");
        long riderId = rider(stage, "마지막하차학생", "boarded");
        CountDownLatch patchHoldsStop = pausePatchWhileHoldingStopUntilArrivalHoldsRun();

        List<Throwable> failures = runConcurrently(() -> {
            record(stage, riderId, "alighted");
            return null;
        }, () -> {
            patchHoldsStop.await(WAIT_LIMIT_SECONDS, TimeUnit.SECONDS);
            arrive(stage);
            return null;
        });

        assertThat(failures).as("교착이면 한쪽이 deadlock detected 로 던진다").isEmpty();
        assertThat(riderStatus(riderId)).isEqualTo("alighted");
        assertThat(runStatus(stage.runId())).as("마지막 하차와 최종 도착이 모두 반영돼 회차가 끝난다").isEqualTo("finished");
    }

    @Test
    @DisplayName("I1 교착 — 마지막 승하차지의 미승차(PATCH)와 회차를 끝내는 도착 처리(운행 종료 강제 출발)가 겹쳐도 교착하지 않는다")
    void 마지막_미승차와_종료_도착이_겹쳐도_교착하지_않는다() throws Exception {
        Stage stage = stage("from_academy");
        rider(stage, "이미하차학생", "alighted");
        long waitingRiderId = rider(stage, "미승차학생", "waiting");
        CountDownLatch patchHoldsStop = pausePatchWhileHoldingStopUntilArrivalHoldsRun();

        List<Throwable> failures = runConcurrently(() -> {
            record(stage, waitingRiderId, "no_show");
            return null;
        }, () -> {
            patchHoldsStop.await(WAIT_LIMIT_SECONDS, TimeUnit.SECONDS);
            arrive(stage);
            return null;
        });

        assertThat(failures).isEmpty();
        assertThat(riderStatus(waitingRiderId)).isEqualTo("no_show");
        assertThat(runStatus(stage.runId())).isEqualTo("finished");
        assertThat(jdbcTemplate.queryForObject("SELECT departed_at FROM run_stop WHERE id = ?", OffsetDateTime.class,
                stage.runStopId())).as("종료가 남은 승하차지를 강제로 출발 처리한다").isNotNull();
    }

    @Test
    @DisplayName("I1 교착 — 하차(PATCH)가 승하차지 행을 쥔 순간 근접 출발 판정이 들어와도 교착 없이 끝나고 출발 알림은 1건이다")
    void 하차와_근접_출발이_겹쳐도_교착하지_않는다() throws Exception {
        Stage stage = stage("from_academy");
        long riderId = rider(stage, "출발경합학생", "boarded");
        long otherRiderId = rider(stage, "남는학생", "boarded");
        CountDownLatch patchHoldsStop = new CountDownLatch(1);
        AtomicBoolean fired = new AtomicBoolean();
        RepositoryReadHooks.afterRead("findLockedByRouteVersionIdAndStopId", () -> {
            if (fired.compareAndSet(false, true)) {
                patchHoldsStop.countDown();
                pause();
            }
        });

        List<Throwable> failures = runConcurrently(() -> {
            record(stage, riderId, "alighted");
            return null;
        }, () -> {
            patchHoldsStop.await(WAIT_LIMIT_SECONDS, TimeUnit.SECONDS);
            depart(stage);
            return null;
        });

        assertThat(failures).isEmpty();
        assertThat(riderStatus(riderId)).isEqualTo("alighted");
        assertThat(riderStatus(otherRiderId)).isEqualTo("boarded");
        assertThat(jdbcTemplate.queryForObject("SELECT departed_at FROM run_stop WHERE id = ?", OffsetDateTime.class,
                stage.runStopId())).as("근접 출발 판정이 그 승하차지를 출발 처리한다").isNotNull();
    }

    @Test
    @DisplayName("I1 교착 — 도착 처리의 앞 승하차지 강제 출발이 회차 행보다 먼저 승하차지 행을 쥐지 않는다(하차 기록이 회차 행을 쥔 채 그 승하차지를 기다릴 때)")
    void 앞_승하차지_강제_출발과_하차_기록이_겹쳐도_교착하지_않는다() throws Exception {
        Stage stage = stage("from_academy");
        long riderId = rider(stage, "앞승하차지학생", "boarded");
        long finalStopId = fixtures.stop(stage.academyId(), "37.510000", "127.010000");
        fixtures.addRunStop(stage.runId(), finalStopId, 2, OffsetDateTime.now(clock).minusMinutes(10));
        long finalRunStopId = jdbcTemplate.queryForObject("SELECT id FROM run_stop WHERE stop_id = ?", Long.class,
                finalStopId);
        CountDownLatch patchHoldsRun = new CountDownLatch(1);
        CountDownLatch arrivalClaimedEarlierStop = new CountDownLatch(1);
        AtomicBoolean fired = new AtomicBoolean();
        // PATCH 가 회차 행과 탑승자 행을 쥔 순간 멈추고, 도착 처리가 앞 승하차지 강제 출발을 시작한 뒤에 승하차지 행을 잡으러 가게 한다.
        RepositoryReadHooks.afterRead("findByIdAndRunIdAndStatusNot", () -> {
            if (fired.compareAndSet(false, true)) {
                patchHoldsRun.countDown();
                await(arrivalClaimedEarlierStop);
                pause();
            }
        });
        RepositoryReadHooks.afterRead("findAllNotDepartedBefore", arrivalClaimedEarlierStop::countDown);

        List<Throwable> failures = runConcurrently(() -> {
            record(stage, riderId, "alighted");
            return null;
        }, () -> {
            patchHoldsRun.await(WAIT_LIMIT_SECONDS, TimeUnit.SECONDS);
            runArrivalCommandService.arrive(stage.driver(), stage.runId(), finalRunStopId);
            return null;
        });

        assertThat(failures).isEmpty();
        assertThat(riderStatus(riderId)).isEqualTo("alighted");
        assertThat(runStatus(stage.runId())).isEqualTo("finished");
    }

    @Test
    @DisplayName("되돌리기 경합 — 출발 선점이 겹쳐도 '되돌려졌는데 출발 알림이 나간' 상태가 생기지 않는다")
    void 되돌리기와_출발_선점이_겹쳐도_되돌린_기록의_알림이_나가지_않는다() throws Exception {
        Stage stage = stage("to_academy");
        long studentId = fixtures.student(stage.academyId(), "되돌리기경합학생");
        BoardingCommandFixtures.GuardianAccount guardian = fixtures.guardian(stage.academyId(), "되돌리기경합보호자");
        fixtures.linkChild(guardian.guardianId(), studentId, OffsetDateTime.now(clock).minusDays(1));
        long riderId = fixtures.runRider(stage.runId(), studentId, stage.stopId());
        jdbcTemplate.update("UPDATE run_rider SET status = 'boarded' WHERE id = ?", riderId);
        entityManager.clear();
        CountDownLatch revertChecked = new CountDownLatch(1);
        AtomicBoolean fired = new AtomicBoolean();
        Runnable pauseAfterDepartureCheck = () -> {
            if (fired.compareAndSet(false, true)) {
                revertChecked.countDown();
                pause();
            }
        };
        RepositoryReadHooks.afterRead("findLockedByRouteVersionIdAndStopId", pauseAfterDepartureCheck);
        RepositoryReadHooks.afterRead("findByRouteVersionIdAndStopId", pauseAfterDepartureCheck);

        List<Throwable> failures = runConcurrently(() -> {
            boardingCommandService.revert(stage.escort(), stage.runId(), riderId, new RiderRevertRequest("잘못 눌렀어요"));
            return null;
        }, () -> {
            revertChecked.await(WAIT_LIMIT_SECONDS, TimeUnit.SECONDS);
            depart(stage);
            return null;
        });

        assertThat(failures).as("되돌리기가 출발 선점에 밀렸다면 STOP_ALREADY_DEPARTED 로 거절되는 것까지가 정상이다")
                .allSatisfy(e -> assertThat(e).isInstanceOfSatisfying(BusinessException.class,
                        be -> assertThat(be.getErrorCode()).isEqualTo(ErrorCode.STOP_ALREADY_DEPARTED)));
        boolean reverted = "waiting".equals(riderStatus(riderId));
        int boardingNotices = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_log WHERE recipient_account_id = ? AND type = 'boarding'",
                Integer.class, guardian.accountId());
        assertThat(reverted && boardingNotices > 0)
                .as("되돌려진 승차 기록(waiting)에 승차 알림이 나갔다 — 출발 알림이 나간 뒤 되돌려진 것이다").isFalse();
        assertThat(reverted || boardingNotices == 1).as("되돌려지지 않았다면 출발 때 승차 알림이 1건 나간다").isTrue();
    }

    /** 상대가 잠금 대기에 들어갈 시간을 준다 — 잠금이 있으면 상대는 이 시간 동안 기다리고, 없으면 그 사이에 커밋해 버린다. */
    private static void pause() {
        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
