package src.backend.global.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import src.backend.run.repository.RunRepository;

/**
 * BR-352 — <b>실제 PostgreSQL</b> 이 낸 잠금 실패가 {@link GlobalExceptionHandler} 에서 갈리는지 본다. JPA 경로에서는 잠금
 * 대기 초과({@code 55P03})와 교착({@code 40P01})이 <b>둘 다</b> {@code CannotAcquireLockException} 으로 나온다(Hibernate 가 둘 다
 * 잠금 예외로 옮긴다) — 예외 클래스만으로는 가를 수 없어 SQLState 로 가른다. 잠금 대기 초과는 과부하라 {@code 503}, 교착은
 * 잠금 순서가 어긋난 코드 결함이라 {@code 500} + 스택이다.
 */
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class DbLockFailureClassificationTest {

    private static final long WAIT_SECONDS = 15;

    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void 스레드를_내린다() {
        pool.shutdownNow();
    }

    @Test
    void 실제_잠금_대기_초과는_503_이고_교착은_500_이다(CapturedOutput output) throws Exception {
        List<Map<String, Object>> runs = jdbcTemplate.queryForList("SELECT id, academy_id FROM run ORDER BY id LIMIT 2");
        Run first = Run.of(runs.get(0));
        Run second = Run.of(runs.get(1));
        Throwable lockTimeout = lockTimeoutAgainstHeldRow(first);
        Throwable deadlock = deadlockVictim(first, second);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController(Map.of(
                "lock-timeout", lockTimeout, "deadlock", deadlock))).setControllerAdvice(new GlobalExceptionHandler()).build();

        mockMvc.perform(get("/boom/lock-timeout"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "3"))
                .andExpect(jsonPath("$.error.code").value("SERVER_BUSY"));
        mockMvc.perform(get("/boom/deadlock"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));

        assertThat(output.getAll()).as("교착은 스택을 남기는 결함 로그다").contains("[unexpected]");
    }

    /** 한 트랜잭션이 행 잠금을 쥔 동안 다른 트랜잭션이 {@code lock_timeout} 300ms 로 같은 행을 잠그려다 실패한 예외. */
    private Throwable lockTimeoutAgainstHeldRow(Run row) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = pool.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            runRepository.findLockedByIdAndAcademyId(row.id(), row.academyId());
            held.countDown();
            awaitQuietly(release);
        }));
        assertThat(held.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        try {
            return failureOf(() -> transactionTemplate.executeWithoutResult(status -> {
                jdbcTemplate.execute("SELECT set_config('lock_timeout', '300ms', true)");
                runRepository.findLockedByIdAndAcademyId(row.id(), row.academyId());
            }));
        } finally {
            release.countDown();
            holder.get(WAIT_SECONDS, TimeUnit.SECONDS);
        }
    }

    /** 두 트랜잭션이 두 행을 반대 순서로 잠가 PostgreSQL 이 한쪽을 교착 희생자로 고른 예외. */
    private Throwable deadlockVictim(Run first, Run second) throws Exception {
        CountDownLatch bothHoldFirst = new CountDownLatch(2);
        Future<Throwable> a = pool.submit(() -> failureOf(() -> lockInOrder(first, second, bothHoldFirst)));
        Future<Throwable> b = pool.submit(() -> failureOf(() -> lockInOrder(second, first, bothHoldFirst)));
        Throwable fromA = a.get(WAIT_SECONDS, TimeUnit.SECONDS);
        Throwable fromB = b.get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(fromA != null ^ fromB != null).as("두 트랜잭션 중 정확히 하나가 교착 희생자다").isTrue();
        return fromA != null ? fromA : fromB;
    }

    private void lockInOrder(Run firstRow, Run secondRow, CountDownLatch bothHoldFirst) {
        transactionTemplate.executeWithoutResult(status -> {
            runRepository.findLockedByIdAndAcademyId(firstRow.id(), firstRow.academyId());
            bothHoldFirst.countDown();
            awaitQuietly(bothHoldFirst);
            runRepository.findLockedByIdAndAcademyId(secondRow.id(), secondRow.academyId());
        });
    }

    private static Throwable failureOf(Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            action.run();
        } catch (RuntimeException e) {
            failure.set(e);
        }
        return failure.get();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record Run(long id, long academyId) {

        static Run of(Map<String, Object> row) {
            return new Run(((Number) row.get("id")).longValue(), ((Number) row.get("academy_id")).longValue());
        }
    }

    @RestController
    static class ThrowingController {

        private final Map<String, Throwable> failures;

        ThrowingController(Map<String, Throwable> failures) {
            this.failures = failures;
        }

        @GetMapping("/boom/{kind}")
        String boom(@PathVariable String kind) throws Throwable {
            throw failures.get(kind);
        }
    }
}
