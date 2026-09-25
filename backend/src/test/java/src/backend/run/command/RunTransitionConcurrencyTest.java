package src.backend.run.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.controller.DriverRunFixtures;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

/**
 * BR-041 — 운행 시작·종료 전이의 동시성(§7 규칙 3). 조회한 상태로 판정하고 변경 감지로 쓰면, 서로의
 * 미커밋 변경을 못 보는 두 트랜잭션이 둘 다 통과하거나(시작 2회) 둘 다 빠진다(종료 누락).
 *
 * <p>{@code @Transactional} 을 쓰지 않는다 — 쓰면 두 스레드가 테스트 트랜잭션을 공유해 경합 자체가
 * 만들어지지 않는다({@code RunConfirmationConcurrencyTest} 와 같은 근거). 그래서 만든 행은 직접 지운다.
 */
@SpringBootTest
class RunTransitionConcurrencyTest {

    private static final long TIMEOUT_SECONDS = 30;

    @Autowired private AcademyRepository academyRepository;
    @Autowired private BusRepository busRepository;
    @Autowired private StopRepository stopRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private ManagerRepository managerRepository;
    @Autowired private AssignmentRepository assignmentRepository;
    @Autowired private RunRepository runRepository;
    @Autowired private ConfirmedRouteRepository confirmedRouteRepository;
    @Autowired private RouteVersionRepository routeVersionRepository;
    @Autowired private RunStopRepository runStopRepository;
    @Autowired private RunRiderRepository runRiderRepository;
    @Autowired private AcademyStaffRepository academyStaffRepository;
    @Autowired private GuardianRepository guardianRepository;
    @Autowired private GuardianStudentRepository guardianStudentRepository;
    @Autowired private ChangeRequestRepository changeRequestRepository;
    @Autowired private RunCompletionService runCompletionService;
    @Autowired private RunStartCommandService runStartCommandService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<Long> academyIds = new ArrayList<>();

    private DriverRunFixtures fixtures() {
        return new DriverRunFixtures(academyRepository, busRepository, stopRepository, studentRepository,
                accountRepository, managerRepository, assignmentRepository, runRepository, confirmedRouteRepository,
                routeVersionRepository, runStopRepository, runRiderRepository, academyStaffRepository,
                guardianRepository, guardianStudentRepository, changeRequestRepository);
    }

    @AfterEach
    void 뒷정리한다() {
        for (Long academyId : academyIds) {
            jdbcTemplate.update("DELETE FROM notification_log WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM run WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM manager WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM student WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM account WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM stop WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM bus WHERE academy_id = ?", academyId);
            jdbcTemplate.update("DELETE FROM academy WHERE id = ?", academyId);
        }
        academyIds.clear();
    }

    /**
     * 하원 종료 보류 중 남은 두 명이 동시에 하차 처리되면 — 각자 자기 하차만 본 채 "아직 1명 남음" 으로
     * 세고 둘 다 종료를 건너뛰어, 탑승자 0명인데 {@code moving} 에 멈춘다. 되살릴 경로가 없다.
     * 장벽이 "두 하차가 모두 쓰인 뒤에 둘 다 센다" 는 순서를 강제해 결과가 실행마다 같다.
     */
    @Test
    @DisplayName("BR-041 — 종료 보류 회차의 마지막 두 명이 동시에 하차해도 운행이 끝난다")
    void 마지막_두_명이_동시에_하차해도_운행이_끝난다() throws Exception {
        DriverRunFixtures fx = fixtures();
        long academyId = fx.academy();
        academyIds.add(academyId);
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.560000", "126.970000");
        OffsetDateTime now = OffsetDateTime.now();
        long runId = fx.confirmedRun(academyId, busId, Direction.FROM_ACADEMY, now, now.minusMinutes(30));
        fx.startRun(runId, now);
        long riderA = fx.rider(runId, fx.student(academyId, "학생A"), stopId, RiderStatus.BOARDED, now);
        long riderB = fx.rider(runId, fx.student(academyId, "학생B"), stopId, RiderStatus.BOARDED, now);
        jdbcTemplate.update("UPDATE run SET finish_pending = true WHERE id = ?", runId);

        CyclicBarrier 둘_다_하차를_썼다 = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Boolean> ended = new ArrayList<>();
        try {
            Future<Boolean> a = pool.submit(하차하고_종료를_묻는다(runId, riderA, 둘_다_하차를_썼다));
            Future<Boolean> b = pool.submit(하차하고_종료를_묻는다(runId, riderB, 둘_다_하차를_썼다));
            ended.add(a.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            ended.add(b.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM run WHERE id = ?", String.class, runId))
                .as("탑승자가 0명이 됐는데 moving 에 남으면 위치 송신·관제 '운행 중' 이 끝나지 않는다")
                .isEqualTo("finished");
        assertThat(ended).as("종료는 정확히 한 번 — 둘 다 true 면 run_ended 가 두 번 나간다").containsOnlyOnce(true);
    }

    /** 시작 버튼 두 번·재시도가 겹치면 둘 다 {@code confirmed} 를 보고 통과해 운행 시작 알림이 두 번 나간다. */
    @Test
    @DisplayName("BR-041 — 같은 회차의 시작 요청 두 건이 겹쳐도 한 건만 시작되고 나머지는 409 다")
    void 시작_요청_두_건이_겹쳐도_한_건만_시작된다() throws Exception {
        DriverRunFixtures fx = fixtures();
        long academyId = fx.academy();
        academyIds.add(academyId);
        long busId = fx.bus(academyId);
        OffsetDateTime now = OffsetDateTime.now();
        long runId = fx.confirmedRun(academyId, busId, Direction.TO_ACADEMY, now, now.minusMinutes(30));
        long driverAccountId = fx.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now);
        AuthUser driver = new AuthUser(driverAccountId, academyId, Role.DRIVER, AccountStatus.ACTIVE);

        CountDownLatch 출발선 = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<String> outcomes = new ArrayList<>();
        try {
            Callable<String> 시작 = () -> {
                출발선.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                try {
                    runStartCommandService.start(driver, runId);
                    return "started";
                } catch (BusinessException e) {
                    return e.getErrorCode().name();
                }
            };
            Future<String> first = pool.submit(시작);
            Future<String> second = pool.submit(시작);
            출발선.countDown();
            outcomes.add(first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            outcomes.add(second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        assertThat(outcomes).containsExactlyInAnyOrder("started", ErrorCode.RUN_ALREADY_STARTED.name());
    }

    private Callable<Boolean> 하차하고_종료를_묻는다(long runId, long riderId, CyclicBarrier barrier) {
        return () -> new TransactionTemplate(transactionManager).execute(status -> {
            OffsetDateTime now = OffsetDateTime.now();
            RunRider rider = runRiderRepository.findById(riderId).orElseThrow();
            rider.alight(now);
            runRiderRepository.saveAndFlush(rider);
            try {
                barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            Run run = runRepository.findById(runId).orElseThrow();
            return runCompletionService.completeIfAllAlighted(run, now);
        });
    }
}
