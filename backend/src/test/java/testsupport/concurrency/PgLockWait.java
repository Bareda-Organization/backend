package testsupport.concurrency;

import java.time.Duration;
import java.util.function.BooleanSupplier;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 다른 연결이 쥔 행 잠금을 기다리는 Postgres 백엔드 수를 센다 — 시험이 잠금을 쥔 채 두 스레드가 실제로 그 잠금에서 줄을 섰는지 고정 대기 없이 확인한다.
 */
public final class PgLockWait {

    private static final Duration LIMIT = Duration.ofSeconds(15);

    private PgLockWait() {
    }

    /** 지금 행 잠금을 기다리는 백엔드 수. {@code pg_stat_activity} 는 트랜잭션마다 캐시되므로 읽기 전에 스냅숏을 버린다. */
    public static int waiting(JdbcTemplate jdbc) {
        jdbc.execute("SELECT pg_stat_clear_snapshot()");
        Integer count = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                + "AND wait_event_type = 'Lock'", Integer.class);
        return count == null ? 0 : count;
    }

    /** 잠금 대기가 {@code expected} 개가 되거나 {@code orDone} 이 참이 될 때까지 기다린다 — 잠금이 없으면 스레드가 끝나 {@code orDone} 으로 빠진다. */
    public static void awaitWaitersOr(JdbcTemplate jdbc, int expected, BooleanSupplier orDone) throws InterruptedException {
        long deadline = System.nanoTime() + LIMIT.toNanos();
        while (waiting(jdbc) < expected && !orDone.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("잠금 대기 " + expected + "개를 " + LIMIT + " 안에 보지 못했다");
            }
            Thread.sleep(25);
        }
    }
}
