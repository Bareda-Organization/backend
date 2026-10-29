package src.backend.run.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import src.backend.observability.metrics.RunConfirmationMetrics;
import src.backend.run.command.RunConfirmationService;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;

/**
 * 한 틱이 느려도 건강한 배치가 {@code down} 으로 보이지 않는다 — 회차 하나가 끝날 때마다 마지막 성공 시각을 남기므로, 마지막 회차가 오래 걸려도
 * 먼저 끝난 회차의 시각이 갱신된다.
 */
class RunConfirmationSchedulerHeartbeatTest {

    private final RunRepository runRepository = mock(RunRepository.class);

    private final RunConfirmationService confirmationService = mock(RunConfirmationService.class);

    private final ConfirmationBatchHeartbeat heartbeat = mock(ConfirmationBatchHeartbeat.class);

    @Test
    void 틱이_끝나기_전에도_먼저_끝난_회차가_마지막_성공_시각을_남긴다() throws Exception {
        Run fast = run(1L);
        Run slow = run(2L);
        when(runRepository.findDueForConfirmation(any(), any(), any())).thenReturn(List.of(fast, slow));
        CountDownLatch releaseSlow = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (invocation.<Long>getArgument(0) == 2L) {
                releaseSlow.await(15, TimeUnit.SECONDS);
            }
            return null;
        }).when(confirmationService).confirmOne(any(Long.class));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        RunConfirmationScheduler scheduler = new RunConfirmationScheduler(runRepository, confirmationService, pool,
                Clock.systemUTC(), mock(RunConfirmationMetrics.class), heartbeat);

        CompletableFuture<Void> tick = CompletableFuture.runAsync(scheduler::confirmDueRuns);
        try {
            verify(heartbeat, timeout(5000)).markSuccess();
            assertThat(tick).as("느린 회차가 아직 도는 동안 틱은 끝나지 않았다").isNotDone();
        } finally {
            releaseSlow.countDown();
            tick.get(15, TimeUnit.SECONDS);
            pool.shutdownNow();
        }
        verify(heartbeat, atLeast(2)).markSuccess();
    }

    private static Run run(long id) {
        Run run = mock(Run.class);
        when(run.getId()).thenReturn(id);
        when(run.getConsecutiveFailures()).thenReturn(0);
        return run;
    }
}
