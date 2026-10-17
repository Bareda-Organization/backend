package src.backend.location.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RT-5 캐시의 비움과 읽기가 겹쳐도 낡은 목록이 남지 않는다 — 결석 학생 채널로 이 버스의 위치가 최대 5분 더 가는 개인정보 노출을 막는다.
 */
class RunBroadcastCacheTest {

    private final RunBroadcastCache cache = new RunBroadcastCache(Clock.systemUTC());

    @Test
    @DisplayName("읽는 중에 비움이 끼면 읽은 값을 저장하지 않고 다음 조회가 다시 읽는다")
    void 읽는_중_비움이_끼면_다음_조회가_다시_읽는다() throws Exception {
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger();

        CompletableFuture<List<Long>> first = CompletableFuture.supplyAsync(() -> cache.students(1L, () -> {
            loads.incrementAndGet();
            loading.countDown();
            await(release);
            return List.of(1L, 2L);
        }));
        assertThat(loading.await(5, TimeUnit.SECONDS)).isTrue();
        cache.evictStudents(1L);
        release.countDown();
        assertThat(first.get(5, TimeUnit.SECONDS)).containsExactly(1L, 2L);

        assertThat(cache.students(1L, () -> {
            loads.incrementAndGet();
            return List.of(1L);
        })).as("비움 뒤 첫 조회는 낡은 목록이 아니라 새로 읽은 값").containsExactly(1L);
        assertThat(loads.get()).isEqualTo(2);
        assertThat(cache.students(1L, () -> {
            loads.incrementAndGet();
            return List.of();
        })).as("그 다음은 저장된 값").containsExactly(1L);
        assertThat(loads.get()).isEqualTo(2);
    }

    /**
     * 검증자의 경합 재현(30만 회 중 5회)과 같은 모양 — 한 스레드는 "DB 값" 을 읽어 캐시에 넣고, 다른 스레드는 DB 값을 바꾼 뒤 비운다. 두 스레드가
     * 끝나면 캐시에는 비움 이후의 값 아니면 아무것도 없어야 한다. 확인과 저장 사이의 틈은 바깥에서 멈출 수 없어 반복으로 겨눈다.
     */
    @Test
    @DisplayName("확인과 저장 사이에 비움이 끼는 경합을 반복해도 낡은 목록이 남지 않는다")
    void 경합을_반복해도_낡은_목록이_남지_않는다() throws Exception {
        AtomicLong database = new AtomicLong();
        int stale = 0;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 100_000; i++) {
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<?> reader = pool.submit(() -> {
                    barrier.await();
                    cache.students(1L, () -> List.of(database.get()));
                    return null;
                });
                Future<?> writer = pool.submit(() -> {
                    barrier.await();
                    database.incrementAndGet();
                    cache.evictStudents(1L);
                    return null;
                });
                reader.get();
                writer.get();
                long cached = cache.students(1L, () -> List.of(-1L)).get(0);
                if (cached != -1L && cached != database.get()) {
                    stale++;
                }
                cache.evictStudents(1L);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(stale).as("비움 뒤에도 남은 낡은 항목 수").isZero();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
