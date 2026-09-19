package src.backend.location.proximity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
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
import src.backend.account.repository.AccountRepository;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.Direction;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

/**
 * {@link RunStopRepository#claimDeparture} 의 <b>동시성</b>(R17-T1 목표 1, Ruling 307 조건부 UPDATE
 * 선점) — 스케줄러 인스턴스 2개가 같은 정차 항목의 출발을 동시에 선점해도 정확히 한 번만 성공해야
 * 한다. {@link RunStopProximityClaimConcurrencyTest} 와 검사 대상 메서드만 다르고 기법은 같다 —
 * {@code WHERE departed_at IS NULL} 조건부 UPDATE 가 행 잠금에서 경합하는 것을, 첫 스레드가 커밋을
 * 미루는 동안 Postgres 가 둘째 스레드를 {@code wait_event_type='Lock', wait_event='transactionid'} 로
 * 재우는 것을 폴링으로 확인한다.
 */
@SpringBootTest
class RunStopDepartureClaimConcurrencyTest {

    private static final long TIMEOUT_SECONDS = 20;

    private static final long POLL_INTERVAL_MILLIS = 50;

    @Autowired
    private RunStopRepository runStopRepository;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private BusRepository busRepository;

    @Autowired
    private StopRepository stopRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private GuardianRepository guardianRepository;

    @Autowired
    private GuardianStudentRepository guardianStudentRepository;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private ConfirmedRouteRepository confirmedRouteRepository;

    @Autowired
    private RouteVersionRepository routeVersionRepository;

    @Autowired
    private RunRiderRepository runRiderRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void 뒷정리한다() {
        String academyIds = "(SELECT id FROM academy WHERE name = '근접알림시험학원')";
        jdbcTemplate.update("DELETE FROM run WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM bus WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM stop WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM academy WHERE name = '근접알림시험학원'");
    }

    @Test
    @DisplayName("R17-T1 목표1 — 같은 정차 항목의 출발을 동시에 선점해도 갱신은 정확히 1행이다")
    void 같은_정차_항목의_출발을_동시에_선점해도_한_번만_성공한다() throws Exception {
        ProximityFixtures fixtures = new ProximityFixtures(academyRepository, busRepository, stopRepository,
                studentRepository, accountRepository, guardianRepository, guardianStudentRepository, runRepository,
                confirmedRouteRepository, routeVersionRepository, runStopRepository, runRiderRepository);
        OffsetDateTime now = OffsetDateTime.now();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long stopId = fixtures.stop(academyId, "37.500000", "127.000000");
        long runId = fixtures.movingRun(academyId, busId, Direction.FROM_ACADEMY, now.plusHours(1), now, now);
        long versionId = fixtures.confirmedRouteWithVersion(runId, now);
        long runStopId = fixtures.runStopForStop(versionId, stopId, 1, now.plusMinutes(10));
        fixtures.arriveStop(runStopId, now);

        CountDownLatch 먼저_들어갔다 = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int 첫번째_갱신수;
        int 두번째_갱신수;
        try {
            Future<Integer> 첫번째 = pool.submit(
                    () -> 먼저_선점하고_상대가_막힐_때까지_커밋을_미룬다(runStopId, 먼저_들어갔다));
            Future<Integer> 두번째 = pool.submit(() -> {
                먼저_들어갔다.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return runStopRepository.claimDeparture(runStopId, OffsetDateTime.now().plusSeconds(1));
            });

            두번째_갱신수 = 두번째.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            첫번째_갱신수 = 첫번째.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        assertThat(첫번째_갱신수 + 두번째_갱신수)
                .as("WHERE departed_at IS NULL 조건부 UPDATE 하나가 멱등성의 전부다 — 합이 2면 두 호출부가 같은 "
                        + "정차지의 출발 이벤트를 두 번 발행하는 것이고, 0이면 아무도 성공 못 한 것이다")
                .isEqualTo(1);
        assertThat(runStopRepository.findById(runStopId).orElseThrow().getDepartedAt()).isNotNull();
    }

    private int 먼저_선점하고_상대가_막힐_때까지_커밋을_미룬다(long runStopId, CountDownLatch 먼저_들어갔다) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            int updated = runStopRepository.claimDeparture(runStopId, OffsetDateTime.now());
            먼저_들어갔다.countDown();
            상대가_대기할_때까지_커밋을_미룬다();
            return updated;
        });
    }

    private void 상대가_대기할_때까지_커밋을_미룬다() {
        long 마감 = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < 마감) {
            // RunStopProximityClaimConcurrencyTest 와 같은 근거 — pg_stat_activity 캐시 스냅숏을
            // 매번 비워야 상대의 대기가 보인다(F5 S3 실측).
            jdbcTemplate.execute("SELECT pg_stat_clear_snapshot()");
            Integer 대기중 = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                            + "AND wait_event_type = 'Lock' AND wait_event = 'transactionid'",
                    Integer.class);
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
