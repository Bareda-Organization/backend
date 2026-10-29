package testsupport.concurrency;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.assertj.core.api.Assertions;

/**
 * 동시성 시험의 공용 도구 — 호출 여러 개를 동시에 띄워 던진 예외를 모으고, 겹침 지점에 도달했는지 확인한다.
 */
public final class Race {

    private static final long WAIT_LIMIT_SECONDS = 60;

    private Race() {
    }

    /** 호출들을 동시에 띄우고 모두 끝날 때까지 기다려 던진 예외를 모아 돌려준다 — 예외 없이 끝나면 빈 목록이다. */
    @SafeVarargs
    public static List<Throwable> run(Callable<?>... tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        List<Throwable> failures = new ArrayList<>();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Callable<?> task : tasks) {
                futures.add(pool.submit(task));
            }
            for (Future<?> future : futures) {
                try {
                    future.get(WAIT_LIMIT_SECONDS, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    failures.add(e.getCause());
                }
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(WAIT_LIMIT_SECONDS, TimeUnit.SECONDS);
        }
        return failures;
    }

    /** 상대가 겹침 지점에 도달했는지 확인한다 — 도달하지 못했으면 겹침이 만들어지지 않은 시험이라 통과로 치지 않는다. */
    public static void awaitReached(CountDownLatch latch, String what) throws InterruptedException {
        Assertions.assertThat(latch.await(WAIT_LIMIT_SECONDS, TimeUnit.SECONDS))
                .as("겹침 지점에 도달하지 못했다: %s", what).isTrue();
    }
}
