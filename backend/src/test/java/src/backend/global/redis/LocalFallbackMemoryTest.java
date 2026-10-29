package src.backend.global.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import testsupport.clock.AdvanceableClock;

/** Redis 가 멈춘 동안 쓰는 인스턴스 안의 기억 — 개수 상한 · 항목별 만료 · 동시 호출에서도 지켜지는 상한과 한 번뿐인 맡기. */
class LocalFallbackMemoryTest {

    private static final Duration TTL = Duration.ofSeconds(30);

    private final AdvanceableClock clock = new AdvanceableClock(Clock.fixed(Instant.parse("2030-04-01T00:00:00Z"), ZoneOffset.UTC));

    @Test
    @DisplayName("기억한 값은 만료 전에 읽히고 만료 시각부터 비어 있다")
    void 만료_시각부터_비어_있다() {
        LocalFallbackMemory<String> memory = new LocalFallbackMemory<>(clock, 10);
        memory.put("a", "값", TTL);

        assertThat(memory.get("a")).contains("값");
        clock.advance(TTL.minusMillis(1));
        assertThat(memory.get("a")).as("만료 1ms 전").contains("값");
        clock.advance(Duration.ofMillis(1));
        assertThat(memory.get("a")).as("만료 시각").isEmpty();
        assertThat(memory.size()).as("만료된 항목은 읽을 때 치워진다").isZero();
    }

    @Test
    @DisplayName("상한을 넘기면 만료된 항목을 먼저 치우고 새 항목을 담는다")
    void 가득_차면_만료된_항목부터_치운다() {
        LocalFallbackMemory<String> memory = new LocalFallbackMemory<>(clock, 3);
        memory.put("old-1", "1", Duration.ofSeconds(5));
        memory.put("old-2", "2", Duration.ofSeconds(5));
        memory.put("live", "3", TTL);
        clock.advance(Duration.ofSeconds(6));

        memory.put("new", "4", TTL);

        assertThat(memory.size()).isEqualTo(2);
        assertThat(memory.get("live")).contains("3");
        assertThat(memory.get("new")).contains("4");
    }

    @Test
    @DisplayName("만료된 항목이 없는데 가득 차면 하나를 버리고 상한을 유지하며 새 항목은 담긴다")
    void 가득_차면_하나를_버린다() {
        LocalFallbackMemory<Integer> memory = new LocalFallbackMemory<>(clock, 3);
        for (int i = 0; i < 4; i++) {
            memory.put("k" + i, i, TTL);
        }

        assertThat(memory.size()).isEqualTo(3);
        assertThat(memory.get("k3")).contains(3);
    }

    @Test
    @DisplayName("같은 키를 덮어써도 상한이 줄지 않고 항목이 늘지 않는다")
    void 같은_키_덮어쓰기는_항목을_늘리지_않는다() {
        LocalFallbackMemory<Integer> memory = new LocalFallbackMemory<>(clock, 2);
        memory.put("a", 1, TTL);
        memory.put("b", 2, TTL);

        memory.put("a", 3, TTL);

        assertThat(memory.size()).isEqualTo(2);
        assertThat(memory.get("a")).contains(3);
        assertThat(memory.get("b")).contains(2);
    }

    @Test
    @DisplayName("키가 없거나 만료됐을 때만 맡을 수 있다")
    void 맡기는_살아_있는_항목이_없을_때만() {
        LocalFallbackMemory<Boolean> memory = new LocalFallbackMemory<>(clock, 10);

        assertThat(memory.claim("k", true, TTL)).isTrue();
        assertThat(memory.claim("k", true, TTL)).as("살아 있는 항목").isFalse();
        clock.advance(TTL);
        assertThat(memory.claim("k", true, TTL)).as("만료 뒤").isTrue();
    }

    @Test
    @DisplayName("여러 스레드가 서로 다른 키를 동시에 넣어도 항목 수는 상한을 한 번도 넘지 않는다")
    void 동시에_넣어도_상한을_넘지_않는다() throws Exception {
        int cap = 10;
        LocalFallbackMemory<Integer> memory = new LocalFallbackMemory<>(clock, cap);
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger maxSeen = new AtomicInteger();
        List<Future<?>> writers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int writer = t;
            writers.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < 2_000; i++) {
                    memory.put("w" + writer + "-" + i, i, TTL);
                }
                return null;
            }));
        }
        Future<?> observer = pool.submit(() -> {
            start.await();
            while (writers.stream().anyMatch(future -> !future.isDone())) {
                maxSeen.accumulateAndGet(memory.size(), Math::max);
            }
            return null;
        });

        start.countDown();
        for (Future<?> writer : writers) {
            writer.get(30, TimeUnit.SECONDS);
        }
        observer.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(maxSeen.get()).as("쓰는 도중 관찰한 최대 항목 수").isLessThanOrEqualTo(cap);
        assertThat(memory.size()).isLessThanOrEqualTo(cap);
    }

    @Test
    @DisplayName("같은 키를 동시에 맡으면 한 스레드만 true 를 받는다")
    void 동시에_맡아도_하나만_성공한다() throws Exception {
        LocalFallbackMemory<Boolean> memory = new LocalFallbackMemory<>(clock, 10);
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            results.add(pool.submit(() -> {
                start.await();
                return memory.claim("same", true, TTL);
            }));
        }

        start.countDown();
        int winners = 0;
        for (Future<Boolean> result : results) {
            winners += result.get(10, TimeUnit.SECONDS) ? 1 : 0;
        }
        pool.shutdownNow();

        assertThat(winners).isEqualTo(1);
    }
}
