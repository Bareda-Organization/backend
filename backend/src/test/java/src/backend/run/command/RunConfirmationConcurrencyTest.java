package src.backend.run.command;

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
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.Direction;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * {@link RunRepository#confirmIfIdle} 의 <b>동시성</b>(Phase 7 목표 2) — 같은 회차를 두 스레드가
 * 동시에 확정하려 해도 정확히 한 번만 성공해야 한다.
 *
 * <p>{@code RunGenerationConcurrencyTest} 와 같은 기법을 쓰되 대상이 다르다 — 그쪽은 INSERT-INSERT
 * 가 UNIQUE 제약에서 경합하고, 이쪽은 <b>같은 행에 대한 UPDATE-UPDATE</b> 가 행 잠금에서 경합한다.
 * 둘 다 Postgres 는 뒤에 온 트랜잭션을 {@code wait_event_type='Lock', wait_event='transactionid'}
 * 로 재우므로 같은 폴링 질의로 "상대가 진짜 막혔는지" 를 확인할 수 있다.
 *
 * <p>{@code @Transactional} 을 쓰지 않는다 — 쓰면 두 스레드가 테스트의 트랜잭션을 공유해 "서로의
 * 커밋을 보지 못하는" 경쟁 상황 자체가 만들어지지 않는다.
 */
@SpringBootTest
class RunConfirmationConcurrencyTest {

    private static final long TIMEOUT_SECONDS = 20;

    private static final long POLL_INTERVAL_MILLIS = 50;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private BusRepository busRepository;

    @Autowired
    private RouteRepository routeRepository;

    @Autowired
    private RouteStopRepository routeStopRepository;

    @Autowired
    private StopRepository stopRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private WeeklyAddressRepository weeklyAddressRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void 뒷정리한다() {
        String academyIds = "(SELECT id FROM academy WHERE name = '" + RunConfirmationFixtures.ACADEMY_NAME + "')";
        jdbcTemplate.update("DELETE FROM run WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM bus WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM academy WHERE name = '" + RunConfirmationFixtures.ACADEMY_NAME + "'");
    }

    /**
     * 목표2 — {@code confirmIfIdle} 을 동시에 두 번 부르면 갱신 행 수의 합이 정확히 1이어야 한다.
     *
     * <p>{@code confirmIfIdle} 만 잰다(노선 계산·저장은 범위 밖) — {@code WHERE status = 'idle'} 조건부
     * UPDATE 하나가 멱등성의 전부라는 그 메서드 javadoc 의 단언을, 실제 동시 스레드로 직접 검증한다.
     */
    @Test
    @DisplayName("목표2 — 같은 회차를 동시에 확정해도 갱신은 정확히 1행이다")
    void 같은_회차를_동시에_확정해도_한_번만_성공한다() throws Exception {
        RunConfirmationFixtures fixtures = new RunConfirmationFixtures(academyRepository, busRepository,
                routeRepository, routeStopRepository, stopRepository, studentRepository, weeklyAddressRepository,
                runRepository);
        long academyId = fixtures.academyWithCoordinates();
        long busId = fixtures.bus(academyId);
        OffsetDateTime departTime = OffsetDateTime.now().plusHours(3);
        long runId = fixtures.idleRun(academyId, busId, java.time.LocalDate.of(2030, 4, 1), Direction.TO_ACADEMY,
                departTime, departTime.minusMinutes(30));

        CountDownLatch 먼저_들어갔다 = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int 첫번째_갱신수;
        int 두번째_갱신수;
        try {
            Future<Integer> 첫번째 = pool.submit(
                    () -> 먼저_확정하고_상대가_막힐_때까지_커밋을_미룬다(runId, 먼저_들어갔다));
            Future<Integer> 두번째 = pool.submit(() -> {
                먼저_들어갔다.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return runRepository.confirmIfIdle(runId, OffsetDateTime.now().plusSeconds(1));
            });

            두번째_갱신수 = 두번째.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            첫번째_갱신수 = 첫번째.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        assertThat(첫번째_갱신수 + 두번째_갱신수)
                .as("WHERE status='idle' 조건부 UPDATE 하나가 멱등성의 전부다 — 합이 2면 둘 다 성공한 것이고, "
                        + "0이면 아무도 성공 못 한 것이다")
                .isEqualTo(1);
        assertThat(runRepository.findById(runId).orElseThrow().getStatus().name()).isEqualTo("CONFIRMED");
    }

    private int 먼저_확정하고_상대가_막힐_때까지_커밋을_미룬다(long runId, CountDownLatch 먼저_들어갔다) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            int updated = runRepository.confirmIfIdle(runId, OffsetDateTime.now());
            먼저_들어갔다.countDown();
            상대가_대기할_때까지_커밋을_미룬다();
            return updated;
        });
    }

    private void 상대가_대기할_때까지_커밋을_미룬다() {
        long 마감 = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < 마감) {
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
