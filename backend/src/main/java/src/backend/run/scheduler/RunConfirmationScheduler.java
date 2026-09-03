package src.backend.run.scheduler;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import src.backend.observability.metrics.RunConfirmationMetrics;
import src.backend.run.command.RunConfirmationService;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.run.repository.RunRepository;

/**
 * 확정 배치의 진입점(RTE-08, ARCHITECTURE §9.2) — 판정 시각이 지난 idle 회차를 모아
 * {@link RunConfirmationService#confirmOne} 을 회차별로 부른다.
 *
 * <p><b>한 회차의 실패가 다른 회차를 막지 않는다</b>(목표 4) — 회차마다 개별 {@code try-catch} 로
 * 감싸 어떤 예외든(기대한 업무 규칙 위반이든 예상 못 한 버그든) 그 회차의 실패로만 기록하고 다음
 * 회차로 넘어간다. 실패한 회차는 {@code idle} 로 남아(자동 롤백 — {@code RunConfirmationPersistence}
 * 의 트랜잭션이 되돌린다) 다음 틱에 다시 대상이 된다.
 *
 * <p><b>배치 크기 상한</b>(목표 6)은 {@link #BATCH_SIZE} 가 한 틱이 집는 회차 수를 제한하고,
 * <b>동시 실행 상한</b>은 {@link RunConfirmationWorkerPoolConfig} 가 만드는 고정 크기 스레드 풀이
 * 제한한다 — 회차 수가 풀 크기를 넘으면 나머지는 앞 작업이 끝날 때까지 큐에서 대기한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunConfirmationScheduler {

    /**
     * 한 틱이 한 번에 집는 상한(목표 6) — 상한 없이 전건을 집으면 회차가 몰린 틱 하나가 워커 풀을
     * 오래 붙든다({@code RunRepository.findByStatusAndConfirmAtLessThanEqualAndCanceledAtIsNullOrderByConfirmAtAsc}
     * 의 {@code pageable} 이 이 값을 받는다).
     */
    static final int BATCH_SIZE = 50;

    private final RunRepository runRepository;

    private final RunConfirmationService confirmationService;

    private final ExecutorService runConfirmationExecutor;

    private final Clock clock;

    private final RunConfirmationMetrics metrics;

    /**
     * 판정 시각이 지난 idle 회차를 확정한다.
     *
     * <p>폴링 주기를 설정으로 받는 이유는 <b>테스트에서 배경 실행을 미루기 위함</b>이다 — 배경
     * 배치가 테스트가 만든 회차를 먼저 집으면 확정 건수·멱등성 단언이 실행 순서에 따라 갈린다
     * ({@code build.gradle} 이 {@code initial-delay-ms} 를 하루로 늦춘다, {@code NotificationOutboxWorker}
     * 와 같은 형태).
     *
     * <p>한 틱 안에서 모든 확정을 {@link CompletableFuture#join()} 으로 기다린 뒤 반환한다 — 그래야
     * 이 메서드를 직접 호출하는 테스트가 완료를 동기적으로 관측할 수 있고, {@code fixedDelay} 특성상
     * 다음 실행도 이 완료 시점부터 계산된다(밀린 틱이 겹쳐 돌지 않는다).
     */
    @Scheduled(fixedDelayString = "${app.run.confirmation.poll-interval-ms:30000}",
            initialDelayString = "${app.run.confirmation.initial-delay-ms:0}")
    public void confirmDueRuns() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Run> dueRuns = runRepository
                .findByStatusAndConfirmAtLessThanEqualAndCanceledAtIsNullOrderByConfirmAtAsc(RunStatus.IDLE, now,
                        PageRequest.of(0, BATCH_SIZE));

        List<CompletableFuture<Void>> tasks = dueRuns.stream()
                .map(run -> CompletableFuture.runAsync(() -> confirmSafely(run.getId()), runConfirmationExecutor))
                .toList();
        CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
    }

    /**
     * 회차 1건을 확정하고, 무엇이 됐든 실패를 삼켜 {@code consecutive_failures} 에만 남긴다(목표 4).
     *
     * <p>{@code BusinessException} 처럼 예상한 실패로 좁히지 않고 {@link Exception} 전체를 잡는다 —
     * 좁히면 예상 못 한 버그(널 참조 등)가 이 스레드를 죽여 {@link CompletableFuture} 가 예외로
     * 완료되고, 그 예외가 {@link #confirmDueRuns} 의 {@code join()} 까지 올라가 <b>이 틱의 나머지
     * 회차 확정 여부와 무관하게 배치 자체가 실패로 끝난다.</b>
     *
     * <p>이 격리 때문에 {@code confirmDueRuns} 자체는 예외를 던지지 않고 정상 반환하므로,
     * {@code @Scheduled} 메서드 전체를 감싸는 {@code schoolbus.scheduler.failures}
     * ({@code ScheduledTaskMetricsAspect})는 이 실패를 절대 못 본다(TECH_DECISIONS §13.1 3행) —
     * 그래서 {@link RunConfirmationMetrics#recordRetryFailure()} 를 여기서 직접 부른다.
     */
    private void confirmSafely(Long runId) {
        try {
            confirmationService.confirmOne(runId);
        } catch (Exception e) {
            log.warn("회차 {} 확정 실패 — 다음 틱에 재시도한다", runId, e);
            runRepository.recordFailure(runId);
            metrics.recordRetryFailure();
        }
    }
}
