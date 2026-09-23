package src.backend.run.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import io.github.resilience4j.bulkhead.BulkheadRegistry;

/**
 * 확정 배치 워커 풀({@link RunConfirmationWorkerPoolConfig#runConfirmationExecutor})의 <b>동시 실행
 * 상한</b>(Phase 7 목표 6-b) — 확정 도메인 로직과 무관하게 이 빈 하나만 떼어 재는 것이 요점이다.
 * 회차 확정을 통해 재려면 외부 지도 API 호출을 인위로 지연시켜야 하는데, 그러면 무엇을 재는지가
 * 흐려진다({@code RunConfirmationSchedulerTest} 는 "무엇이 확정되는가", 이 시험은 "몇 개가 동시에
 * 도는가" 로 관심사를 가른다).
 */
@SpringBootTest
class RunConfirmationWorkerPoolConfigTest {

    private static final long TIMEOUT_SECONDS = 20;

    @Autowired
    private ExecutorService runConfirmationExecutor;

    @Autowired
    private BulkheadRegistry bulkheadRegistry;

    /**
     * 풀이 격벽보다 크면 한 틱에 도래한 회차 중 격벽을 넘는 몫은 <b>공급자에 닿지도 못하고</b> 직선 근사로
     * 확정된다(격벽 대기 0). 2026-09-23 실측 — 풀 8 · 격벽 4 에서 데모 회차 60건 중 53건이 직선 근사였다.
     * ARCHITECTURE §9.4 는 "계산 워커 풀 크기를 외부 지도 API 레이트리밋에 맞춤" 이다.
     */
    @Test
    void 풀_크기는_지도_API_격벽_상한을_넘지_않는다() {
        int bulkheadLimit = bulkheadRegistry.bulkhead("mapRoute").getBulkheadConfig().getMaxConcurrentCalls();

        assertThat(((ThreadPoolExecutor) runConfirmationExecutor).getMaximumPoolSize())
                .isLessThanOrEqualTo(bulkheadLimit);
    }

    @Test
    @DisplayName("목표6-b — 동시 실행 수는 설정된 풀 크기를 실제로 넘지 않는다")
    void 동시_실행_수는_설정된_풀_크기를_넘지_않는다() throws InterruptedException {
        int configuredPoolSize = ((ThreadPoolExecutor) runConfirmationExecutor).getMaximumPoolSize();
        int taskCount = configuredPoolSize + 5; // 풀 크기보다 많이 던져야 "상한이 실제로 막는지" 를 잰다.
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxObserved = new AtomicInteger();
        CountDownLatch poolSaturated = new CountDownLatch(configuredPoolSize);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch allFinished = new CountDownLatch(taskCount);

        for (int i = 0; i < taskCount; i++) {
            runConfirmationExecutor.submit(() -> {
                int now = active.incrementAndGet();
                maxObserved.updateAndGet(prev -> Math.max(prev, now));
                poolSaturated.countDown();
                try {
                    release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    active.decrementAndGet();
                    allFinished.countDown();
                }
            });
        }

        boolean saturatedInTime = poolSaturated.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(saturatedInTime).as("설정된 풀 크기만큼은 동시에 시작될 수 있어야 한다(상한이 너무 낮게 걸리지 않았는지)")
                .isTrue();
        // 나머지(taskCount - poolSize)는 큐에서 대기 중이라 아직 active 를 올리지 않았을 수 있다 —
        // maxObserved 는 지금까지 관측된 최댓값이고, 상한을 넘었다면 이 시점에 이미 넘어 있어야 한다.
        assertThat(maxObserved.get()).as("동시 실행 수가 설정된 풀 크기(" + configuredPoolSize + ")를 넘으면 안 된다")
                .isEqualTo(configuredPoolSize);

        release.countDown();
        boolean finishedInTime = allFinished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(finishedInTime).as("모든 작업이 뒷정리 없이 남아 다음 시험을 오염시키지 않아야 한다").isTrue();
    }
}
