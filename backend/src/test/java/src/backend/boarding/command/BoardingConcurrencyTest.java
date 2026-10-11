package src.backend.boarding.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.dto.RiderStatusUpdateRequest;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.exception.command.EmergencyCommandService;
import src.backend.exception.dto.EmergencyRaiseRequest;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.security.AuthUser;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.entity.RunStop;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.command.StopDepartureService;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import testsupport.clock.FixedClock20300401Config;
import testsupport.concurrency.RepositoryReadHooks;

/**
 * 승하차·비상 신고의 동시 요청 — BR-226(같은 {@code client_key} 가 동시에 두 번 오면 두 번째가 UNIQUE 위반 500 이 되던
 * 결함)과 BR-229(같은 승하차지의 마지막 두 명이 동시에 미승차 처리되면 어느 쪽도 {@code stop_skipped} 를 못 세우던
 * 결함)의 재현본이다.
 *
 * <p>경합 창은 "읽고 나서 쓰기 전" 지점에 배리어를 세워 강제한다 — 두 요청이 <b>둘 다 읽은 뒤에야</b> 쓰기로 넘어가야
 * 서로의 미커밋 변경을 못 본 상태가 된다. 읽기 전에 요청끼리 직렬화하는 가드가 있으면 뒤 요청은 배리어에 오지 못하고
 * 앞 요청이 시간 만료로 배리어를 깨고 지나간다.
 *
 * <p>{@code @Transactional} 을 쓰지 않는다 — 두 스레드가 서로의 커밋을 보지 못하는 경합 자체가 사라진다.
 */
@SpringBootTest
@Import({FixedClock20300401Config.class, RepositoryReadHooks.class})
class BoardingConcurrencyTest {

    private static final long BARRIER_LIMIT_SECONDS = 3;

    private static final long WAIT_LIMIT_SECONDS = 60;

    private static final String ACADEMY_NAME = "승하차처리시험학원";

    @Autowired
    private BoardingCommandService boardingCommandService;

    @Autowired
    private EmergencyCommandService emergencyCommandService;

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
    private ManagerRepository managerRepository;

    @Autowired
    private AssignmentRepository assignmentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private Clock clock;

    @Autowired
    private RunRiderRepository runRiderRepository;

    @Autowired
    private StopDepartureService stopDepartureService;

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
        jdbcTemplate.update("DELETE FROM emergency_alert WHERE academy_id IN " + academyIds);
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

    /** 같은 호출을 두 스레드로 동시에 띄우고 둘 다 끝날 때까지 기다린다 — 예외는 그대로 던진다. */
    private void runConcurrently(Callable<?> first, Callable<?> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = List.of(pool.submit(first), pool.submit(second));
            for (Future<?> future : futures) {
                future.get(WAIT_LIMIT_SECONDS, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(WAIT_LIMIT_SECONDS, TimeUnit.SECONDS);
        }
    }

    /** 두 스레드가 다 도착해야 풀리는 배리어 — 상대가 못 오면(직렬화됨) 시간 만료로 풀린다. */
    private static void meetOrGiveUp(CyclicBarrier barrier) {
        try {
            barrier.await(BARRIER_LIMIT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception 시간_만료_또는_배리어_깨짐) {
            // 상대를 더 기다리지 않고 진행한다.
        }
    }

    private long movingRunWithEscort(long academyId, long[] accountIdOut) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long busId = fixtures.bus(academyId);
        long runId = fixtures.movingRun(academyId, busId, now.minusMinutes(10), now.minusMinutes(40));
        accountIdOut[0] = fixtures.assignedManager(managerRepository, assignmentRepository, academyId, runId,
                ManagerRole.ESCORT, now);
        return runId;
    }

    @Test
    @DisplayName("BR-226 — 같은 client_key 로 승하차 처리를 동시에 두 번 보내도 500 이 아니라 재생 응답이고 이력은 1행이다")
    void 같은_client_key_승하차_동시_재전송은_재생으로_답한다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long academyId = fixtures.academy();
        long[] escortAccountId = new long[1];
        long runId = movingRunWithEscort(academyId, escortAccountId);
        long stopId = fixtures.stop(academyId, "37.500000", "127.000000");
        long riderId = fixtures.runRider(runId, fixtures.student(academyId, "동시학생"), stopId);
        AuthUser escort = new AuthUser(escortAccountId[0], academyId, Role.ESCORT, AccountStatus.ACTIVE);
        UUID clientKey = UUID.randomUUID();
        RiderStatusUpdateRequest request = new RiderStatusUpdateRequest("boarded", "manual", clientKey, now);

        CyclicBarrier bothMissedReplay = new CyclicBarrier(2);
        RepositoryReadHooks.afterRead("findByClientKey", () -> meetOrGiveUp(bothMissedReplay));

        runConcurrently(() -> boardingCommandService.updateStatus(escort, runId, riderId, request),
                () -> boardingCommandService.updateStatus(escort, runId, riderId, request));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM rider_status_history WHERE client_key = ?",
                Integer.class, clientKey))
                .as("같은 키의 재전송은 이력을 늘리지 않는다 — 2면 둘 다 새 처리로 들어간 것이다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("BR-267 — 같은 탑승자에 서로 다른 client_key 로 boarded 를 동시에 보내면 한 건만 처리되고 이력은 1행이다")
    void 다른_client_key_동시_승차는_한_건만_처리된다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long academyId = fixtures.academy();
        long[] escortAccountId = new long[1];
        long runId = movingRunWithEscort(academyId, escortAccountId);
        long stopId = fixtures.stop(academyId, "37.500000", "127.000000");
        long riderId = fixtures.runRider(runId, fixtures.student(academyId, "동시학생"), stopId);
        AuthUser escort = new AuthUser(escortAccountId[0], academyId, Role.ESCORT, AccountStatus.ACTIVE);

        // 두 요청이 탑승자를 waiting 으로 읽은 뒤에야 각자 boarded 를 쓰게 한다 — 읽기 전에 직렬화하는 가드가 있으면 뒤 요청은 배리어에 못 온다.
        CyclicBarrier bothRead = new CyclicBarrier(2);
        RepositoryReadHooks.afterRead("findByIdAndRunIdAndStatusNot", () -> meetOrGiveUp(bothRead));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<?>> futures;
        try {
            futures = List.of(
                    pool.submit(() -> boardingCommandService.updateStatus(escort, runId, riderId,
                            new RiderStatusUpdateRequest("boarded", "manual", UUID.randomUUID(), now))),
                    pool.submit(() -> boardingCommandService.updateStatus(escort, runId, riderId,
                            new RiderStatusUpdateRequest("boarded", "manual", UUID.randomUUID(), now))));
            long rejected = 0;
            for (Future<?> future : futures) {
                try {
                    future.get(WAIT_LIMIT_SECONDS, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(src.backend.global.error.BusinessException.class);
                    rejected++;
                }
            }
            assertThat(rejected).as("뒤 요청은 이미 boarded 인 탑승자에 대한 전이라 거절된다").isEqualTo(1);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(WAIT_LIMIT_SECONDS, TimeUnit.SECONDS);
        }

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM rider_status_history WHERE run_rider_id = ?",
                Integer.class, riderId))
                .as("같은 탑승자의 boarded 는 이력 1행이다 — 2면 두 요청이 모두 waiting 을 읽고 각자 승차를 기록한 것이다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("BR-226 — 같은 client_key 로 비상 신고를 동시에 두 번 보내도 500 이 아니라 재생 응답이고 신고는 1건이다")
    void 같은_client_key_비상_신고_동시_재전송은_재생으로_답한다() throws Exception {
        long academyId = fixtures.academy();
        long[] escortAccountId = new long[1];
        long runId = movingRunWithEscort(academyId, escortAccountId);
        AuthUser escort = new AuthUser(escortAccountId[0], academyId, Role.ESCORT, AccountStatus.ACTIVE);
        UUID clientKey = UUID.randomUUID();
        EmergencyRaiseRequest request = new EmergencyRaiseRequest("ACCIDENT", null, clientKey, null, null, null);

        CyclicBarrier bothMissedReplay = new CyclicBarrier(2);
        RepositoryReadHooks.afterRead("findByClientKey", () -> meetOrGiveUp(bothMissedReplay));

        runConcurrently(() -> emergencyCommandService.raise(escort, runId, request),
                () -> emergencyCommandService.raise(escort, runId, request));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM emergency_alert WHERE client_key = ?",
                Integer.class, clientKey))
                .as("같은 키의 재전송은 신고를 늘리지 않는다 — 2면 둘 다 새 접수로 들어간 것이다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("BR-229 — 같은 승하차지의 마지막 두 명이 동시에 미승차 처리되면 어느 한쪽이 stop_skipped 를 세운다")
    void 마지막_두_명이_동시에_미승차면_stop_skipped_가_세워진다() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long academyId = fixtures.academy();
        long[] escortAccountId = new long[1];
        long runId = movingRunWithEscort(academyId, escortAccountId);
        long stopId = fixtures.stop(academyId, "37.500000", "127.000000");
        long runStopId = fixtures.confirmedRunStop(runId, stopId, now.minusMinutes(30));
        long firstRider = fixtures.runRider(runId, fixtures.student(academyId, "학생A"), stopId);
        long secondRider = fixtures.runRider(runId, fixtures.student(academyId, "학생B"), stopId);
        AuthUser escort = new AuthUser(escortAccountId[0], academyId, Role.ESCORT, AccountStatus.ACTIVE);

        // 두 요청이 남은 사람 수를 센 뒤에야 각자 결과를 반영하게 한다 — 서로의 미커밋 미승차를 못 본 채 센다.
        CyclicBarrier bothCounted = new CyclicBarrier(2);
        RepositoryReadHooks.afterRead("countByRunIdAndStopIdAndStatusNotIn", () -> meetOrGiveUp(bothCounted));

        runConcurrently(
                () -> boardingCommandService.updateStatus(escort, runId, firstRider,
                        new RiderStatusUpdateRequest("no_show", "manual", UUID.randomUUID(), now)),
                () -> boardingCommandService.updateStatus(escort, runId, secondRider,
                        new RiderStatusUpdateRequest("no_show", "manual", UUID.randomUUID(), now)));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM run_rider WHERE run_id = ? AND status = 'no_show'",
                Integer.class, runId)).as("두 명 다 미승차로 반영됐다").isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT change FROM run_stop WHERE id = ?", String.class, runStopId))
                .as("남은 사람이 0명이 됐으니 그 승하차지는 건너뜀 표시가 있어야 한다")
                .isEqualTo("skipped");
    }

    // ── Ruling 878 — 출발 뒤에 기록된 승차·하차·미승차의 학부모 알림 ─────────────────────────

    /** 이미 확정 노선·정차 항목이 있는 이동 중 회차 한 건과 그 승하차지에 탈 학생(보호자 연결) 한 명. */
    private record Stage(long academyId, long runId, long stopId, long runStopId, AuthUser escort) {
    }

    private Stage stage() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long academyId = fixtures.academy();
        long[] escortAccountId = new long[1];
        long runId = movingRunWithEscort(academyId, escortAccountId);
        long stopId = fixtures.stop(academyId, "37.500000", "127.000000");
        long runStopId = fixtures.confirmedRunStop(runId, stopId, now.minusMinutes(30));
        return new Stage(academyId, runId, stopId, runStopId,
                new AuthUser(escortAccountId[0], academyId, Role.ESCORT, AccountStatus.ACTIVE));
    }

    /** 학생 한 명과 그 보호자 — 반환값은 {탑승자 id, 보호자 계정 id}. */
    private long[] riderWithGuardian(Stage stage, String studentName) {
        long studentId = fixtures.student(stage.academyId(), studentName);
        BoardingCommandFixtures.GuardianAccount guardian = fixtures.guardian(stage.academyId(), studentName + "보호자");
        fixtures.linkChild(guardian.guardianId(), studentId, OffsetDateTime.now(clock).minusDays(1));
        return new long[] {fixtures.runRider(stage.runId(), studentId, stage.stopId()), guardian.accountId()};
    }

    private void toFromAcademyWithBoardedRider(Stage stage, long riderId) {
        jdbcTemplate.update("UPDATE run SET direction = 'from_academy' WHERE id = ?", stage.runId());
        jdbcTemplate.update("UPDATE run_rider SET status = 'boarded' WHERE id = ?", riderId);
        entityManager.clear();
    }

    private void record(Stage stage, long riderId, String status) {
        boardingCommandService.updateStatus(stage.escort(), stage.runId(), riderId,
                new RiderStatusUpdateRequest(status, "manual", UUID.randomUUID(), OffsetDateTime.now(clock)));
    }

    private void depart(Stage stage) {
        RunStop runStop = runStopRepository.findById(stage.runStopId()).orElseThrow();
        stopDepartureService.claimAndPublish(runStop, stage.runId(), stage.academyId(), OffsetDateTime.now(clock));
    }

    private int notifications(long guardianAccountId, String type) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_log WHERE recipient_account_id = ? AND type = ?", Integer.class,
                guardianAccountId, type);
    }

    private String notificationBody(long guardianAccountId, String type) {
        return jdbcTemplate.queryForObject(
                "SELECT body FROM notification_log WHERE recipient_account_id = ? AND type = ?", String.class,
                guardianAccountId, type);
    }

    @Test
    @DisplayName("Ruling 878 — 출발 전 기록은 알림이 나가지 않고 출발 때 1회만 나간다(Ruling 854 그대로)")
    void 출발_전_기록은_출발_때_한_번만_알린다() {
        Stage stage = stage();
        long[] rider = riderWithGuardian(stage, "출발전학생");

        record(stage, rider[0], "boarded");
        assertThat(notifications(rider[1], "boarding")).as("출발 전 기록은 즉시 알림이 없다").isZero();

        depart(stage);
        assertThat(notifications(rider[1], "boarding")).as("출발 때 확정 결과로 1회").isEqualTo(1);
    }

    @Test
    @DisplayName("Ruling 878 — 출발 뒤 기록한 승차·미승차(등원)는 기록 즉시 그 학생 학부모에게 1건씩 알림이 나간다")
    void 출발_뒤_등원_승차와_미승차는_즉시_알린다() {
        Stage stage = stage();
        long[] boardedRider = riderWithGuardian(stage, "승차학생");
        long[] noShowRider = riderWithGuardian(stage, "미승차학생");
        depart(stage);
        assertThat(notifications(boardedRider[1], "boarding") + notifications(noShowRider[1], "no_show"))
                .as("둘 다 waiting 인 채 출발했으니 출발 때 알림은 없다").isZero();

        record(stage, boardedRider[0], "boarded");
        record(stage, noShowRider[0], "no_show");

        assertThat(notifications(boardedRider[1], "boarding")).isEqualTo(1);
        assertThat(notificationBody(boardedRider[1], "boarding")).isEqualTo("승차학생 학생이 버스에 탑승했습니다.");
        assertThat(notifications(noShowRider[1], "no_show")).isEqualTo(1);
        assertThat(notificationBody(noShowRider[1], "no_show"))
                .isEqualTo("미승차학생 학생이 아직 버스에 탑승하지 않았습니다. 확인해 주세요.");
        assertThat(notifications(boardedRider[1], "no_show") + notifications(noShowRider[1], "boarding"))
                .as("다른 학생의 보호자에게는 가지 않는다").isZero();
    }

    @Test
    @DisplayName("Ruling 878·881 — 출발 뒤 기록한 하차와 하원 미승차는 즉시 나가고 하원 미승차는 하차 미확인 문구다")
    void 출발_뒤_하원_하차와_미승차는_즉시_알린다() {
        Stage stage = stage();
        long[] alightedRider = riderWithGuardian(stage, "하차학생");
        long[] noShowRider = riderWithGuardian(stage, "하원미승차학생");
        toFromAcademyWithBoardedRider(stage, alightedRider[0]);
        depart(stage);

        record(stage, alightedRider[0], "alighted");
        record(stage, noShowRider[0], "no_show");

        assertThat(notifications(alightedRider[1], "alighting")).isEqualTo(1);
        assertThat(notificationBody(alightedRider[1], "alighting")).isEqualTo("하차학생 학생이 버스에서 하차했습니다.");
        assertThat(notificationBody(noShowRider[1], "no_show"))
                .isEqualTo("하원미승차학생 학생의 하차가 확인되지 않았습니다. 학원에 확인해 주세요.");
    }

    @Test
    @DisplayName("Ruling 878 — 출발 뒤 되돌리기는 거절되고 알림이 늘지 않으며, 같은 client_key 재전송도 알림을 늘리지 않는다")
    void 출발_뒤_되돌리기와_재전송은_알림을_늘리지_않는다() {
        Stage stage = stage();
        long[] rider = riderWithGuardian(stage, "되돌리기학생");
        depart(stage);
        UUID clientKey = UUID.randomUUID();
        RiderStatusUpdateRequest request = new RiderStatusUpdateRequest("boarded", "manual", clientKey,
                OffsetDateTime.now(clock));
        boardingCommandService.updateStatus(stage.escort(), stage.runId(), rider[0], request);
        boardingCommandService.updateStatus(stage.escort(), stage.runId(), rider[0], request);

        assertThat(org.junit.jupiter.api.Assertions.assertThrows(src.backend.global.error.BusinessException.class,
                () -> boardingCommandService.revert(stage.escort(), stage.runId(), rider[0],
                        new src.backend.boarding.dto.RiderRevertRequest("잘못 눌렀어요"))).getErrorCode())
                .isEqualTo(src.backend.global.error.ErrorCode.STOP_ALREADY_DEPARTED);

        assertThat(notifications(rider[1], "boarding")).isEqualTo(1);
    }

    /** 기록이 run_stop 행 잠금을 쥔 순간에 출발 처리가 들어와도 알림은 정확히 1건이다(출발 때 묶음이 그 기록을 읽는다). */
    @Test
    @DisplayName("Ruling 878 동시성 — 기록이 먼저 잠금을 쥔 순간 출발 처리가 들어와도 알림은 정확히 1건이다")
    void 기록이_먼저면_출발_때_묶음이_읽어_정확히_1건() throws Exception {
        Stage stage = stage();
        long[] rider = riderWithGuardian(stage, "경합학생A");
        CountDownLatch recordHoldsLock = new CountDownLatch(1);
        AtomicBoolean fired = new AtomicBoolean();
        Runnable pauseWhileHoldingLock = () -> {
            if (fired.compareAndSet(false, true)) {
                recordHoldsLock.countDown();
                pause();
            }
        };
        RepositoryReadHooks.afterRead("findLockedByRouteVersionIdAndStopId", pauseWhileHoldingLock);
        RepositoryReadHooks.afterRead("findByRouteVersionIdAndStopId", pauseWhileHoldingLock);

        runConcurrently(() -> {
            record(stage, rider[0], "boarded");
            return null;
        }, () -> {
            recordHoldsLock.await(WAIT_LIMIT_SECONDS, TimeUnit.SECONDS);
            depart(stage);
            return null;
        });

        assertThat(notifications(rider[1], "boarding")).as("0 이면 둘 다 서로의 미커밋을 못 본 것이다").isEqualTo(1);
    }

    /** 출발 처리가 먼저 잠금을 쥔 순간에 기록이 들어와도 알림은 정확히 1건이다(기록이 출발을 확인하고 즉시 보낸다). */
    @Test
    @DisplayName("Ruling 878 동시성 — 출발 처리가 먼저 잠금을 쥔 순간 기록이 들어와도 알림은 정확히 1건이다")
    void 출발이_먼저면_기록이_즉시_보내_정확히_1건() throws Exception {
        Stage stage = stage();
        long[] rider = riderWithGuardian(stage, "경합학생B");
        CountDownLatch departureClaimed = new CountDownLatch(1);
        AtomicBoolean fired = new AtomicBoolean();
        RepositoryReadHooks.afterRead("findFinalizedByRunIdAndStopId", () -> {
            if (fired.compareAndSet(false, true)) {
                departureClaimed.countDown();
                pause();
            }
        });

        runConcurrently(() -> {
            depart(stage);
            return null;
        }, () -> {
            departureClaimed.await(WAIT_LIMIT_SECONDS, TimeUnit.SECONDS);
            record(stage, rider[0], "boarded");
            return null;
        });

        assertThat(notifications(rider[1], "boarding")).as("0 이면 기록이 출발을 못 보았고 출발 묶음은 기록을 못 본 것이다")
                .isEqualTo(1);
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
