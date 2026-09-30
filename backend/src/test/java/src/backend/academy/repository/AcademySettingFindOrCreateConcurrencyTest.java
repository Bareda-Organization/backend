package src.backend.academy.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 설정 행이 없는 학원에 두 요청이 동시에 {@code findOrCreate} 해도 둘 다 성공한다(BR-246 — 동시 첫 GET·PATCH).
 *
 * <p>{@code save()} 는 PK 를 직접 지정한 엔티티라 {@code merge} 경로이고 INSERT 가 커밋 시점까지 미뤄져 원래의
 * {@code catch} 는 발동하지 않는다 — 뒤 요청이 커밋에서 PK 위반으로 실패한다. 한쪽이 INSERT 를 쥔 채 커밋을 미루고,
 * 상대가 그 잠금을 기다리는 것을 확인한 뒤에야 커밋해 두 트랜잭션이 반드시 겹치게 한다.
 * {@code @Transactional} 을 붙이지 않는다 — 스레드마다 별개 트랜잭션이어야 한다.
 */
@SpringBootTest
class AcademySettingFindOrCreateConcurrencyTest {

    private static final long TIMEOUT_SECONDS = 20;

    @Autowired
    private AcademySettingRepository academySettingRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long academyId;

    @BeforeEach
    void 설정_행이_없는_학원을_만든다() {
        academyId = jdbcTemplate.queryForObject(
                "INSERT INTO academy (code, name, region, status) VALUES ('BR246ACAD', 'BR246', '서울', 'active') "
                        + "RETURNING id", Long.class);
    }

    @AfterEach
    void 뒷정리한다() {
        jdbcTemplate.update("DELETE FROM academy_setting WHERE academy_id = ?", academyId);
        jdbcTemplate.update("DELETE FROM academy WHERE id = ?", academyId);
    }

    @Test
    void 동시에_처음_만들어도_둘_다_같은_행을_받고_500_이_부재한다() throws Exception {
        CountDownLatch firstInserted = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                int minutes = academySettingRepository.findOrCreate(academyId).getNoShowWaitMinutes();
                academySettingRepository.flush();
                firstInserted.countDown();
                waitUntilSomeoneWaitsOnLock();
                return minutes;
            }));
            Future<Integer> second = pool.submit(() -> {
                assertThat(firstInserted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
                return new TransactionTemplate(transactionManager).execute(
                        status -> academySettingRepository.findOrCreate(academyId).getNoShowWaitMinutes());
            });

            assertThat(first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isPositive();
            assertThat(second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("뒤 요청도 PK 위반 없이 앞 요청이 만든 행을 받아야 한다").isPositive();
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM academy_setting WHERE academy_id = ?", Integer.class, academyId)).isEqualTo(1);
    }

    /** 상대가 이 DB 에서 잠금을 기다리는 것이 보일 때까지 — 고정 대기가 아니라 상태를 물어 기다린다. */
    private void waitUntilSomeoneWaitsOnLock() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject("SELECT count(*) FROM pg_stat_activity "
                    + "WHERE datname = current_database() AND wait_event_type = 'Lock'", Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
