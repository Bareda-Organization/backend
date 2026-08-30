package src.backend.request.command;

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
import src.backend.bus.repository.BusRepository;
import src.backend.request.entity.ChangeRequestStatus;
import src.backend.request.repository.BoardingIntentRepository;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.StudentRepository;

/**
 * {@link ChangeRequestAutoRejectionPersistence#autoRejectOne} 의 <b>동시성</b>(Phase 8 T6 목표 4) —
 * 폴링과 {@code moving} 종결이 같은 건을 동시에 집어도 정확히 한 번만 성공해야 한다.
 *
 * <p>{@code RunConfirmationConcurrencyTest} 와 같은 기법이다 — 두 스레드가
 * {@code change_request.id} 하나에 대한 조건부 UPDATE({@code WHERE status = 'pending'})를 동시에
 * 시도하면, Postgres 가 뒤에 온 트랜잭션을 {@code wait_event_type='Lock', wait_event='transactionid'}
 * 로 재운다 — 그 대기를 폴링으로 확인한 뒤에야 먼저 온 트랜잭션이 커밋하게 한다.
 *
 * <p>{@code @Transactional} 을 쓰지 않는다 — 쓰면 두 스레드가 테스트의 트랜잭션을 공유해 "서로의
 * 커밋을 보지 못하는" 경쟁 상황 자체가 만들어지지 않는다.
 */
@SpringBootTest
class ChangeRequestAutoRejectionConcurrencyTest {

    private static final long TIMEOUT_SECONDS = 20;

    private static final long POLL_INTERVAL_MILLIS = 50;

    @Autowired
    private AcademyRepository academyRepository;

    @Autowired
    private BusRepository busRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private BoardingIntentRepository boardingIntentRepository;

    @Autowired
    private ChangeRequestRepository changeRequestRepository;

    @Autowired
    private ConfirmedRouteRepository confirmedRouteRepository;

    @Autowired
    private RouteVersionRepository routeVersionRepository;

    @Autowired
    private ChangeRequestAutoRejectionPersistence persistence;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void 뒷정리한다() {
        String academyIds = "(SELECT id FROM academy WHERE name = '" + ChangeRequestAutoRejectFixtures.ACADEMY_NAME
                + "')";
        jdbcTemplate.update("DELETE FROM notification_log WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM run WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM student WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM account WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM bus WHERE academy_id IN " + academyIds);
        jdbcTemplate.update("DELETE FROM academy WHERE name = '" + ChangeRequestAutoRejectFixtures.ACADEMY_NAME + "'");
    }

    /**
     * 목표4 — {@code autoRejectOne} 을 동시에 두 번 부르면 정확히 한 번만 실제 전이가 일어나야 한다.
     *
     * <p>폴링과 {@code moving} 종결이 같은 요청을 동시에 집는 상황을 그대로 재현한다 —
     * {@code ChangeRequestRepository.autoRejectIfPending} 의 {@code WHERE status = 'pending'} 조건부
     * UPDATE 하나가 멱등성의 전부라는 그 메서드 javadoc 의 단언을, 실제 동시 스레드로 직접 검증한다.
     */
    @Test
    @DisplayName("목표4 — 같은 변경 요청을 동시에 거절해도 전이는 정확히 한 번이다")
    void 같은_요청을_동시에_거절해도_한_번만_성공한다() throws Exception {
        ChangeRequestAutoRejectFixtures fixtures = new ChangeRequestAutoRejectFixtures(academyRepository,
                busRepository, studentRepository, accountRepository, runRepository, boardingIntentRepository,
                changeRequestRepository, confirmedRouteRepository, routeVersionRepository);
        OffsetDateTime now = OffsetDateTime.now();
        long academyId = fixtures.academy();
        long busId = fixtures.bus(academyId);
        long studentId = fixtures.student(academyId, "학생1");
        long parentId = fixtures.parentAccount(academyId, "학부모1");
        long runId = fixtures.run(academyId, busId, now.plusHours(2));
        long changeRequestId = fixtures.pendingChangeRequest(academyId, runId, studentId, parentId,
                now.minusMinutes(10), now.minusMinutes(1));

        CountDownLatch 먼저_들어갔다 = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        boolean 첫번째_성공;
        boolean 두번째_성공;
        try {
            Future<Boolean> 첫번째 = pool.submit(
                    () -> 먼저_거절하고_상대가_막힐_때까지_커밋을_미룬다(changeRequestId, now, 먼저_들어갔다));
            Future<Boolean> 두번째 = pool.submit(() -> {
                먼저_들어갔다.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return persistence.autoRejectOne(changeRequestId, now.plusSeconds(1));
            });

            두번째_성공 = 두번째.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            첫번째_성공 = 첫번째.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        assertThat(첫번째_성공 ^ 두번째_성공)
                .as("WHERE status='pending' 조건부 UPDATE 하나가 멱등성의 전부다 — 둘 다 성공하거나 둘 다 실패하면 안 된다")
                .isTrue();
        assertThat(changeRequestRepository.findById(changeRequestId).orElseThrow().getStatus())
                .isEqualTo(ChangeRequestStatus.AUTO_REJECTED);
    }

    private boolean 먼저_거절하고_상대가_막힐_때까지_커밋을_미룬다(long changeRequestId, OffsetDateTime decidedAt,
            CountDownLatch 먼저_들어갔다) {
        Boolean result = new TransactionTemplate(transactionManager).execute(status -> {
            boolean updated = persistence.autoRejectOne(changeRequestId, decidedAt);
            먼저_들어갔다.countDown();
            상대가_대기할_때까지_커밋을_미룬다();
            return updated;
        });
        return Boolean.TRUE.equals(result);
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
