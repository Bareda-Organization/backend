package src.backend.boarding.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
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
import src.backend.routing.repository.RunStopRepository;
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
}
