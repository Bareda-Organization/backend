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
 * {@link RunStopRepository#claimProximityNotice} 의 <b>동시성</b>(목표 15, Ruling 210 조건부 UPDATE
 * 선점) — 스케줄러 인스턴스 2개가 같은 정차 항목을 동시에 판정해도 선점은 정확히 한 번만 성공해야
 * 한다.
 *
 * <p>{@code RunConfirmationConcurrencyTest} 와 같은 기법이다 — 같은 행에 대한 UPDATE-UPDATE 가
 * 행 잠금에서 경합하므로, 첫 스레드가 커밋을 미루는 동안 Postgres 가 둘째 스레드를
 * {@code wait_event_type='Lock', wait_event='transactionid'} 로 재우는 것을 폴링으로 확인한다.
 *
 * <p>이 클래스는 {@link ProximityNotificationService#judgeOne} 전체가 아니라 저장소 메서드 하나만
 * 겨눈다 — 목표 15가 요구하는 것은 그 조건부 UPDATE 의 동시성이지, 위치 읽기·거리 판정까지 포함한
 * 전체 흐름의 동시성이 아니다({@code judgeOne} 은 위치를 Redis 단건 읽기로 얻어 두 스레드가 같은
 * 값을 읽는 경쟁이 새로 끼어들 뿐, 이 목표가 검증하려는 지점을 흐린다).
 */
@SpringBootTest
class RunStopProximityClaimConcurrencyTest {

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
    @DisplayName("목표15 — 같은 정차 항목을 동시에 선점해도 갱신은 정확히 1행이다")
    void 같은_정차_항목을_동시에_선점해도_한_번만_성공한다() throws Exception {
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

        CountDownLatch 먼저_들어갔다 = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int 첫번째_갱신수;
        int 두번째_갱신수;
        try {
            Future<Integer> 첫번째 = pool.submit(
                    () -> 먼저_선점하고_상대가_막힐_때까지_커밋을_미룬다(runStopId, 먼저_들어갔다));
            Future<Integer> 두번째 = pool.submit(() -> {
                먼저_들어갔다.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return runStopRepository.claimProximityNotice(runStopId, OffsetDateTime.now().plusSeconds(1));
            });

            두번째_갱신수 = 두번째.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            첫번째_갱신수 = 첫번째.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        assertThat(첫번째_갱신수 + 두번째_갱신수)
                .as("WHERE proximity_notified_at IS NULL 조건부 UPDATE 하나가 멱등성의 전부다 — 합이 2면 두 스케줄러 "
                        + "인스턴스가 같은 학부모에게 알림을 두 번 발행하는 것이고, 0이면 아무도 성공 못 한 것이다")
                .isEqualTo(1);
        assertThat(runStopRepository.findById(runStopId).orElseThrow().getProximityNotifiedAt()).isNotNull();
    }

    private int 먼저_선점하고_상대가_막힐_때까지_커밋을_미룬다(long runStopId, CountDownLatch 먼저_들어갔다) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            int updated = runStopRepository.claimProximityNotice(runStopId, OffsetDateTime.now());
            먼저_들어갔다.countDown();
            상대가_대기할_때까지_커밋을_미룬다();
            return updated;
        });
    }

    private void 상대가_대기할_때까지_커밋을_미룬다() {
        long 마감 = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < 마감) {
            // pg_stat_activity 는 트랜잭션 단위로 캐시된다(PostgreSQL 16 · stats_fetch_consistency
            // 기본값 cache) — 이 트랜잭션이 처음 읽은 스냅숏이 끝까지 재사용되므로, 상대가 대기에
            // 들어가기 전에 첫 조회가 나가면 그 뒤로는 몇 번을 물어도 0 이 돌아온다. 그러면 이 루프가
            // 상한을 다 쓰고 바깥 Future.get 이 그보다 먼저 만료해 TimeoutException 만 남는다
            // (F5 S3 실측 — 상대는 44초 내내 Lock/transactionid 로 대기 중이었는데 0 이 보였다)
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
