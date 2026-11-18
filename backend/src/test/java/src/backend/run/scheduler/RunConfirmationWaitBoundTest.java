package src.backend.run.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import src.backend.observability.metrics.RunConfirmationMetrics;
import src.backend.run.command.RunConfirmationService;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;

/**
 * 확정 하나가 돌아오지 않아도 한 틱은 정해 둔 기다림 한도 안에 끝난다 — 한도가 없으면 스케줄러 잠금(5분)이 풀린 뒤에도 틱이 매달려 있어
 * 다음 인스턴스의 틱과 겹친다. 한도에 닿은 틱은 정상 종료로 표시하지 않고(마지막 성공 시각을 갱신하지 않는다) 늦은 확정은 풀 안에서 계속 돈다.
 */
class RunConfirmationWaitBoundTest {

    private static final Duration BOUND = Duration.ofMillis(300);

    private final RunRepository runRepository = mock(RunRepository.class);

    private final RunConfirmationService confirmationService = mock(RunConfirmationService.class);

    private final ConfirmationBatchHeartbeat heartbeat = mock(ConfirmationBatchHeartbeat.class);

    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    private final CountDownLatch neverReturns = new CountDownLatch(1);

    @AfterEach
    void 정리한다() {
        neverReturns.countDown();
        pool.shutdownNow();
    }

    @Test
    @DisplayName("돌아오지 않는 확정이 있어도 틱은 한도 안에 끝나고 정상 종료로 표시하지 않는다")
    void 돌아오지_않는_확정이_있어도_틱은_한도_안에_끝난다() throws Exception {
        Run hanging = mock(Run.class);
        when(hanging.getId()).thenReturn(1L);
        when(runRepository.findDueForConfirmation(any(), any(), any())).thenReturn(List.of(hanging));
        doAnswer(invocation -> {
            neverReturns.await(30, TimeUnit.SECONDS);
            return null;
        }).when(confirmationService).confirmOne(any(Long.class));
        RunConfirmationScheduler scheduler = new RunConfirmationScheduler(runRepository, confirmationService, pool,
                Clock.systemUTC(), mock(RunConfirmationMetrics.class), heartbeat, BOUND);

        CompletableFuture<Void> tick = CompletableFuture.runAsync(scheduler::confirmDueRuns);

        tick.get(5, TimeUnit.SECONDS);
        assertThat(tick).isCompleted();
        verify(heartbeat, never()).markSuccess();
    }
}
