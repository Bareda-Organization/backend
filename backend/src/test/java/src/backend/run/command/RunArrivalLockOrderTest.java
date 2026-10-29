package src.backend.run.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.run.controller.DriverRunFixtures;
import src.backend.run.repository.RunRepository;
import testsupport.clock.AdvanceableClock;
import testsupport.clock.AdvanceableClockConfig;
import testsupport.concurrency.PgLockWait;

/**
 * 정차지 도착 처리(§4.5)의 시각은 회차 행 잠금을 얻은 <b>뒤</b>에 정한다 — 잠금을 기다리는 동안 앞선 요청이 끝난 시각보다 이른 시각이 도착 기록에 남으면 도착 순서가 커밋 순서와 어긋난다. 운행 중이
 * 아닌 회차는 어느 정차지도 건드리기 전에 거절한다(앞 정차지 강제 출발이 먼저 돌지 않는다).
 */
@SpringBootTest
@Import(AdvanceableClockConfig.class)
class RunArrivalLockOrderTest {

    @Autowired private ApplicationContext applicationContext;
    @Autowired private RunArrivalCommandService arrivalService;
    @Autowired private RunRepository runRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private Clock clock;
    @MockitoSpyBean private StopDepartureService stopDepartureService;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void 뒷정리한다() {
        executor.shutdownNow();
        ((AdvanceableClock) clock).reset();
        String academies = "(SELECT id FROM academy WHERE name = '" + DriverRunFixtures.ACADEMY_NAME + "')";
        jdbcTemplate.update("DELETE FROM notification_log WHERE academy_id IN " + academies);
        jdbcTemplate.update("DELETE FROM run WHERE academy_id IN " + academies);
        jdbcTemplate.update("DELETE FROM manager WHERE academy_id IN " + academies);
        jdbcTemplate.update("DELETE FROM account WHERE academy_id IN " + academies);
        jdbcTemplate.update("DELETE FROM stop WHERE academy_id IN " + academies);
        jdbcTemplate.update("DELETE FROM bus WHERE academy_id IN " + academies);
        jdbcTemplate.update("DELETE FROM academy WHERE name = '" + DriverRunFixtures.ACADEMY_NAME + "'");
    }

    @Test
    @DisplayName("잠금을 기다리는 사이 시계가 흘렀으면 도착 시각은 잠금을 얻은 시각이다")
    void 도착_시각은_잠금을_얻은_뒤의_시각이다() throws Exception {
        Setup s = 회차를_만든다(true);
        AdvanceableClock advanceable = (AdvanceableClock) clock;

        Instant afterWait = new TransactionTemplate(transactionManager).execute(status -> {
            runRepository.findLockedByIdAndAcademyId(s.runId, s.academyId).orElseThrow();
            Future<?> pending = executor.submit(() -> arrivalService.arrive(s.driver(), s.runId, s.firstRunStopId));
            try {
                PgLockWait.awaitWaitersOr(jdbcTemplate, 1, pending::isDone);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            advanceable.advance(Duration.ofMinutes(5));
            s.pending = pending;
            return advanceable.instant();
        });

        s.pending.get(30, TimeUnit.SECONDS);
        OffsetDateTime arrivedAt = jdbcTemplate.queryForObject("SELECT arrived_at FROM run_stop WHERE id = ?",
                OffsetDateTime.class, s.firstRunStopId);
        assertThat(arrivedAt.toInstant()).as("잠금을 기다리기 전 시각으로 기록되면 5분 먼저 도착한 것이 된다")
                .isAfterOrEqualTo(afterWait);
    }

    @Test
    @DisplayName("운행 중이 아닌 회차는 앞 정차지 강제 출발을 부르기 전에 RUN_NOT_MOVING 이다")
    void 운행_중이_아니면_정차지를_건드리기_전에_거절한다() {
        Setup s = 회차를_만든다(false);

        assertThatThrownBy(() -> arrivalService.arrive(s.driver(), s.runId, s.firstRunStopId))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RUN_NOT_MOVING));
        verify(stopDepartureService, never()).forceAllBefore(anyLong(), anyLong(), anyLong(), any());
    }

    private Setup 회차를_만든다(boolean moving) {
        DriverRunFixtures fx = DriverRunFixtures.of(applicationContext);
        long academyId = fx.academy();
        long busId = fx.bus(academyId);
        long firstStop = fx.stop(academyId, "37.560000", "126.970000");
        long secondStop = fx.stop(academyId, "37.561000", "126.971000");
        OffsetDateTime now = OffsetDateTime.now(clock);
        long runId = fx.confirmedRun(academyId, busId, Direction.TO_ACADEMY, now, now.minusMinutes(30));
        if (moving) {
            fx.startRun(runId, now);
        }
        long driverAccountId = fx.assignedManager(academyId, runId, ManagerRole.DRIVER, "기사", now);
        long versionId = fx.confirmedRouteWithVersion(runId, now);
        long firstRunStopId = fx.runStopForStop(versionId, firstStop, 1, now);
        fx.runStopForStop(versionId, secondStop, 2, now.plusMinutes(5));
        return new Setup(academyId, runId, driverAccountId, firstRunStopId);
    }

    private static final class Setup {
        final long academyId;
        final long runId;
        final long driverAccountId;
        final long firstRunStopId;
        Future<?> pending;

        Setup(long academyId, long runId, long driverAccountId, long firstRunStopId) {
            this.academyId = academyId;
            this.runId = runId;
            this.driverAccountId = driverAccountId;
            this.firstRunStopId = firstRunStopId;
        }

        AuthUser driver() {
            return new AuthUser(driverAccountId, academyId, Role.DRIVER, AccountStatus.ACTIVE);
        }
    }
}
