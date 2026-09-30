package src.backend.admin.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import src.backend.academy.repository.AcademyRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Role;
import src.backend.global.common.enums.Weekday;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.command.RunCommandService;
import src.backend.run.command.RunConfirmationFixtures;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;
import testsupport.clock.FixedClock20300401Config;

/**
 * BR-270 — 강제 확정이 취소 검사를 통과한 <b>뒤</b> 관계자가 그 회차를 취소하면 {@code 409 RUN_CANCELED} 다.
 *
 * <p>{@code RUN_NOT_IDLE} 은 "이미 확정됐다" 는 뜻이라 취소를 알려 주지 못한다. 순서는 결정적으로 만든다 — 취소
 * 트랜잭션이 회차 행을 잠근 채 <b>강제 확정이 조건부 갱신({@code confirmIfIdle})에서 실제로 DB 잠금을 기다리는 것을
 * 확인한 뒤</b> 커밋한다. 갱신은 커밋된 취소를 보고 0행이 되므로 강제 확정이 진 쪽이다. 실제 커밋을 남기므로 자기
 * 표시가 붙은 학원의 행만 직접 지운다.
 */
@SpringBootTest
@Import(FixedClock20300401Config.class)
class RunForceConfirmCancelRaceTest {

    private static final LocalDate SERVICE_DATE = LocalDate.of(2030, 4, 1);

    private static final long TIMEOUT_SECONDS = 20;

    private static final long POLL_INTERVAL_MILLIS = 50;

    @Autowired private RunForceConfirmCommandService forceConfirmService;
    @Autowired private RunCommandService runCommandService;
    @Autowired private AcademyRepository academyRepository;
    @Autowired private BusRepository busRepository;
    @Autowired private RouteRepository routeRepository;
    @Autowired private RouteStopRepository routeStopRepository;
    @Autowired private StopRepository stopRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private WeeklyAddressRepository weeklyAddressRepository;
    @Autowired private RunRepository runRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private Clock clock;

    @AfterEach
    void 뒷정리한다() {
        String academyIds = "(SELECT id FROM academy WHERE name = '" + RunConfirmationFixtures.ACADEMY_NAME + "')";
        jdbcTemplate.update("DELETE FROM run WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM route WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM student WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM stop WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM bus WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM academy WHERE name = '" + RunConfirmationFixtures.ACADEMY_NAME + "'");
    }

    @Test
    @DisplayName("BR-270 — 취소 검사 뒤 취소가 커밋되면 강제 확정은 RUN_NOT_IDLE 이 아니라 409 RUN_CANCELED 이다")
    void 검사_뒤_취소되면_RUN_CANCELED_이다() throws Exception {
        RunConfirmationFixtures fx = new RunConfirmationFixtures(academyRepository, busRepository, routeRepository,
                routeStopRepository, stopRepository, studentRepository, weeklyAddressRepository, runRepository);
        long academyId = fx.academyWithCoordinates();
        long busId = fx.bus(academyId);
        long stopId = fx.stop(academyId, "37.510000", "127.010000");
        fx.route(academyId, busId, Weekday.MON, Direction.TO_ACADEMY, stopId);
        long studentId = fx.student(academyId, "재원생");
        fx.verifiedAddress(studentId, stopId, Weekday.MON, Direction.TO_ACADEMY, "37.510000", "127.010000");
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime depart = now.plusMinutes(10); // confirm_at = 출발 − 30분 이 이미 지났다(ck_run_confirm_at)
        long runId = fx.idleRun(academyId, busId, SERVICE_DATE, Direction.TO_ACADEMY, depart, depart.minusMinutes(30));
        AuthUser staff = new AuthUser(1L, academyId, Role.STAFF, AccountStatus.ACTIVE);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        AtomicReference<Future<Optional<ErrorCode>>> 강제확정 = new AtomicReference<>();
        Optional<ErrorCode> result;
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                runCommandService.cancel(staff, runId); // 회차 행을 잠그고 취소 표시 — 아직 커밋 전
                강제확정.set(pool.submit(() -> 강제확정한다(runId)));
                강제확정이_DB_잠금을_기다릴_때까지_커밋을_미룬다();
            }); // 여기서 취소가 커밋된다
            result = 강제확정.get().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        assertThat(result)
                .as("확정에서 진 원인이 취소인데 RUN_NOT_IDLE(이미 확정됨)로 답하면 관계자가 원인을 알 수 없다")
                .contains(ErrorCode.RUN_CANCELED);
    }

    private Optional<ErrorCode> 강제확정한다(long runId) {
        try {
            forceConfirmService.forceConfirm(runId, 1L, "정체 회차 강제 확정");
            return Optional.empty();
        } catch (BusinessException e) {
            return Optional.of(e.getErrorCode());
        }
    }

    /** 강제 확정 세션이 잠금 대기에 들어간 것을 물어서 확인한다. 상한에 걸리면 그대로 커밋하고 뒤의 검사가 드러낸다. */
    private void 강제확정이_DB_잠금을_기다릴_때까지_커밋을_미룬다() {
        long 마감 = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < 마감) {
            // pg_stat_activity 는 트랜잭션 단위로 캐시되므로 매번 비운다(AssignmentConcurrencyTest 실측)
            jdbcTemplate.execute("SELECT pg_stat_clear_snapshot()");
            Integer 대기중 = jdbcTemplate.queryForObject("SELECT count(*) FROM pg_stat_activity "
                    + "WHERE datname = current_database() AND wait_event_type = 'Lock'", Integer.class);
            if (대기중 != null && 대기중 > 0) {
                return;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
