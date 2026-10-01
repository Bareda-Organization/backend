package src.backend.academy.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;

/**
 * 설정 행이 없는 학원에 두 요청이 동시에 {@code findOrCreate} 해도 둘 다 성공한다(BR-246 — 동시 첫 GET·PATCH).
 *
 * <p>{@code save()} 는 PK 를 직접 지정한 엔티티라 {@code merge} 경로이고 INSERT 가 커밋 시점까지 미뤄져 원래의
 * {@code catch} 는 발동하지 않는다 — 뒤 요청이 커밋에서 PK 위반으로 실패한다. 한쪽이 INSERT 를 쥔 채 커밋을 미루고,
 * 상대가 그 잠금을 기다리는 것을 확인한 뒤에야 커밋해 두 트랜잭션이 반드시 겹치게 한다.
 * {@code @Transactional} 을 붙이지 않는다 — 스레드마다 별개 트랜잭션이어야 한다.
 *
 * <p>잠금 대기 조회는 <b>트랜잭션 밖(시험 스레드)</b>에서 한다. {@code pg_stat_activity} 는 트랜잭션 안에서 첫 조회 때 찍은
 * 접속 목록·상태·쿼리 문장을 끝까지 재사용한다(잠금 대기 여부만 실시간). 앞 요청의 트랜잭션 안에서 반복 조회하면 뒤 요청이
 * <b>새로 여는 DB 연결</b>은 목록에 없어 영영 세지 못하고, 20초를 채운 뒤 {@code TimeoutException} 으로 끝난다. 시험용 풀은
 * {@code minimum-idle=1} 이라 전체 실행 중 놀던 연결이 줄어 있으면 뒤 요청이 늘 새 연결을 연다 — 단독 실행은 시작 직후라 남은
 * 연결을 재사용해 통과한다. 그래서 시험이 풀을 최소로 줄여 놓고 시작한다({@link #shrinkPoolToMinimum}).
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

    @Autowired
    private DataSource dataSource;

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
        shrinkPoolToMinimum();
        CountDownLatch firstInserted = new CountDownLatch(1);
        CountDownLatch secondIsWaiting = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                int minutes = academySettingRepository.findOrCreate(academyId).getNoShowWaitMinutes();
                academySettingRepository.flush();
                firstInserted.countDown();
                assertThat(awaitLatch(secondIsWaiting)).as("뒤 요청이 잠금 대기에 들어가기 전에는 커밋하지 않는다").isTrue();
                return minutes;
            }));
            Future<Integer> second = pool.submit(() -> {
                assertThat(awaitLatch(firstInserted)).isTrue();
                return new TransactionTemplate(transactionManager).execute(
                        status -> academySettingRepository.findOrCreate(academyId).getNoShowWaitMinutes());
            });

            assertThat(awaitLatch(firstInserted)).isTrue();
            assertThat(secondInsertIsWaitingOnLock()).as("뒤 요청의 INSERT 가 앞 요청의 잠금을 기다려야 두 트랜잭션이 겹친 것이다")
                    .isTrue();
            secondIsWaiting.countDown();

            assertThat(first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isPositive();
            assertThat(second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("뒤 요청도 PK 위반 없이 앞 요청이 만든 행을 받아야 한다").isPositive();
        } finally {
            secondIsWaiting.countDown();
            pool.shutdownNow();
            pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM academy_setting WHERE academy_id = ?", Integer.class, academyId)).isEqualTo(1);
    }

    /** 놀고 있는 연결을 닫아 풀을 {@code minimum-idle} 로 줄인다 — 뒤 요청이 새 연결을 열어야 하는 전체 실행 중간의 상태를 만든다. */
    private void shrinkPoolToMinimum() throws Exception {
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
        pool.softEvictConnections();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (pool.getTotalConnections() != hikari.getMinimumIdle()
                || pool.getIdleConnections() != pool.getTotalConnections()) {
            assertThat(System.nanoTime()).as("풀이 최소 크기로 줄지 않았다").isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    private boolean awaitLatch(CountDownLatch latch) {
        try {
            return latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 뒤 요청의 {@code academy_setting} INSERT 가 이 DB 에서 잠금을 기다리는 것이 보일 때까지 — 고정 대기가 아니라 상태를 물어
     * 기다린다. 트랜잭션 밖에서 불러야 한다(클래스 설명) — 호출마다 새 스냅샷을 읽는다.
     */
    private boolean secondInsertIsWaitingOnLock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject("SELECT count(*) FROM pg_stat_activity "
                    + "WHERE datname = current_database() AND wait_event_type = 'Lock' "
                    + "AND query LIKE 'INSERT INTO academy_setting%'", Integer.class);
            if (waiting != null && waiting > 0) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }
}
