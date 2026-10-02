package src.backend.admin.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
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
import src.backend.global.common.enums.Direction;
import src.backend.global.error.BusinessException;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.repository.ManagerRepository;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.command.RunCompletionService;
import src.backend.run.controller.DriverRunFixtures;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

/**
 * 강제 종료와 동승자의 마지막 하차(자동 종료)가 같은 회차에 겹치는 경우(R47 Ruling 724) — 어느 쪽이 먼저 행을 잡든 <b>한쪽만</b>
 * 회차를 끝낸다. 먼저 읽어 판정하고 쓰면 둘 다 "이동 중" 을 보고 통과해 회차가 두 번 끝나고(종료 알림 2회 · 감사 행과 실제 종료자의
 * 불일치) 한 번 더 {@code finished_at} 이 덮인다.
 *
 * <p>{@code @Transactional} 을 쓰지 않는다 — 쓰면 두 스레드가 시험 트랜잭션을 공유해 경합 자체가 생기지 않는다
 * ({@code RunTransitionConcurrencyTest} 와 같은 근거). 선행 쪽이 행 잠금을 쥔 채 커밋을 미루고, 후행 쪽이 그 잠금을 기다리는 것이
 * 데이터베이스에서 확인된 뒤에야 커밋시켜 순서를 고정한다 — 쉬는 시간으로 맞추지 않는다. 그래서 만든 행은 직접 지운다.
 */
@SpringBootTest
class RunForceFinishConcurrencyTest {

    private static final long TIMEOUT_SECONDS = 30;

    private static final long LOCK_WAIT_SECONDS = 10;

    private static final String FINISHED_BY_FORCE = "forced";

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
    @Autowired private RunForceFinishCommandService runForceFinishCommandService;
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
            jdbcTemplate.update("DELETE FROM audit_log WHERE academy_id = ?", academyId);
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

    /** 운행일이 5일 전이고 종료가 보류된 채 탑승자 1명이 남은 하원 회차 — 그 한 명이 하차하면 자동 종료가 성립한다. */
    private long[] 보류된_옛_회차와_마지막_탑승자() {
        DriverRunFixtures fx = fixtures();
        long academyId = fx.academy();
        academyIds.add(academyId);
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.560000", "126.970000");
        OffsetDateTime now = OffsetDateTime.now();
        long runId = fx.confirmedRun(academyId, busId, Direction.FROM_ACADEMY, now, now.minusMinutes(30));
        fx.startRun(runId, now);
        long riderId = fx.rider(runId, fx.student(academyId, "학생A"), stopId, RiderStatus.BOARDED, now);
        jdbcTemplate.update("UPDATE run SET service_date = ?, finish_pending = true WHERE id = ?",
                LocalDate.now().minusDays(5), runId);
        return new long[] {runId, riderId};
    }

    /**
     * 락 대기자가 생길 때까지 기다린다 — 후행 스레드가 선행 스레드의 행 잠금에 막힌 것을 데이터베이스에서 직접 본다.
     *
     * <p><b>조회 전에 통계 스냅샷을 비운다.</b> 이 조회는 선행 스레드의 열린 트랜잭션 안에서 돈다(같은 스레드의 연결을 쓴다).
     * PostgreSQL 은 {@code pg_stat_activity} 를 트랜잭션이 끝날 때까지 첫 조회 시점 그대로 캐시한다 — 첫 조회가 후행 스레드가 막히기
     * 전이면 상대가 막혀 있어도 계속 0 으로 읽어 시간 초과가 난다(전체 시험 묶음에서 간헐로 실패했고, psql 로 같은 트랜잭션 안의
     * 두 번째 조회가 0 · 스냅샷을 비운 뒤 1 로 갈리는 것을 확인했다). 제한 시간은 결과를 받는 쪽({@code TIMEOUT_SECONDS})보다 짧다 —
     * 같은 시각에 겹치면 원인 없는 {@code TimeoutException} 만 남는다.
     */
    private void 누군가_행_잠금을_기다릴_때까지_기다린다() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LOCK_WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            jdbcTemplate.queryForObject("SELECT pg_stat_clear_snapshot()::text", String.class);
            Integer waiting = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM pg_stat_activity "
                    + "WHERE datname = current_database() AND wait_event_type = 'Lock'", Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new IllegalStateException("후행 스레드가 행 잠금을 기다리는 상태가 확인되지 않았다 — 세션 상태: "
                + jdbcTemplate.queryForList("SELECT pid, state, wait_event_type, wait_event, left(query, 100) AS query "
                        + "FROM pg_stat_activity WHERE datname = current_database()"));
    }

    private Callable<Boolean> 하차하고_종료를_묻는다(long runId, long riderId, Runnable 잠금을_쥔_뒤) {
        return () -> new TransactionTemplate(transactionManager).execute(status -> {
            OffsetDateTime now = OffsetDateTime.now();
            RunRider rider = runRiderRepository.findById(riderId).orElseThrow();
            rider.alight(now);
            runRiderRepository.saveAndFlush(rider);
            Run run = runRepository.findById(runId).orElseThrow();
            boolean ended = runCompletionService.completeIfAllAlighted(run, now);
            잠금을_쥔_뒤.run();
            return ended;
        });
    }

    /** 강제 종료를 부른 결과 — 성공이면 {@code forced}, 거절이면 에러 코드 이름. 서비스가 자기 트랜잭션을 연다. */
    private String 강제_종료한다(long runId) {
        try {
            runForceFinishCommandService.forceFinish(runId, 1L, "운행일이 지나 콘솔에서 종료");
            return FINISHED_BY_FORCE;
        } catch (BusinessException e) {
            return e.getErrorCode().name();
        }
    }

    private <T> T 기다려_받는다(Future<T> future) throws Exception {
        return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("마지막 하차가 회차 행을 먼저 잡으면 — 자동 종료만 성공하고 강제 종료는 409 RUN_NOT_MOVING · 감사 행 0")
    void 마지막_하차가_먼저_종료하면_강제_종료는_409_이다() throws Exception {
        long[] ids = 보류된_옛_회차와_마지막_탑승자();
        long runId = ids[0];
        long riderId = ids[1];
        CountDownLatch 잠금을_쥐었다 = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        boolean autoEnded;
        String forceOutcome;
        try {
            Future<Boolean> auto = pool.submit(하차하고_종료를_묻는다(runId, riderId, () -> {
                잠금을_쥐었다.countDown();
                try {
                    누군가_행_잠금을_기다릴_때까지_기다린다();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            Future<String> force = pool.submit(() -> {
                잠금을_쥐었다.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return 강제_종료한다(runId);
            });
            autoEnded = 기다려_받는다(auto);
            forceOutcome = 기다려_받는다(force);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        assertThat(autoEnded).as("먼저 잠금을 쥔 마지막 하차가 회차를 끝낸다").isTrue();
        assertThat(forceOutcome).as("진 쪽의 강제 종료는 갱신 조건(이동 중)이 거짓이라 거절된다 — 둘 다 성공하면 회차가 두 번 끝난다")
                .isEqualTo("RUN_NOT_MOVING");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM run WHERE id = ?", String.class, runId))
                .isEqualTo("finished");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE target_type = 'run' AND target_id = ?", Integer.class, runId))
                .as("강제 종료가 지면 '관리자가 끝냈다' 는 감사 행을 남기지 않는다").isZero();
    }

    @Test
    @DisplayName("강제 종료가 회차 행을 먼저 잡으면 — 강제 종료만 성공하고 마지막 하차의 자동 종료는 일어나지 않는다 · 감사 행 1")
    void 강제_종료가_먼저_종료하면_자동_종료는_일어나지_않는다() throws Exception {
        long[] ids = 보류된_옛_회차와_마지막_탑승자();
        long runId = ids[0];
        long riderId = ids[1];
        CountDownLatch 강제_종료를_썼다 = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        boolean autoEnded;
        String forceOutcome;
        try {
            // 서비스의 트랜잭션이 바깥 트랜잭션에 합류하므로, 바깥이 커밋할 때까지 회차 행 잠금이 풀리지 않는다.
            Future<String> force = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                String outcome = 강제_종료한다(runId);
                강제_종료를_썼다.countDown();
                try {
                    누군가_행_잠금을_기다릴_때까지_기다린다();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return outcome;
            }));
            Future<Boolean> auto = pool.submit(() -> {
                강제_종료를_썼다.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return 하차하고_종료를_묻는다(runId, riderId, () -> { }).call();
            });
            forceOutcome = 기다려_받는다(force);
            autoEnded = 기다려_받는다(auto);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        assertThat(forceOutcome).as("먼저 잠금을 쥔 강제 종료가 회차를 끝낸다").isEqualTo(FINISHED_BY_FORCE);
        assertThat(autoEnded).as("진 쪽의 자동 종료는 이미 finished 를 보고 건너뛴다 — true 면 종료 알림이 한 번 더 나간다").isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE target_type = 'run' AND target_id = ?", Integer.class, runId))
                .isEqualTo(1);
    }
}
