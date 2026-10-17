package src.backend.run.scheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import src.backend.global.common.logging.RateLimitedWarn;
import src.backend.global.error.BusinessException;
import src.backend.observability.metrics.RunConfirmationMetrics;
import src.backend.run.command.RunConfirmationService;
import src.backend.run.domain.RunConfirmationPolicy;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;

/**
 * 확정 배치의 진입점(RTE-08, ARCHITECTURE §9.2) — 판정 시각이 지난 idle 회차를 모아
 * {@link RunConfirmationService#confirmOne} 을 회차별로 부른다.
 *
 * <p><b>한 회차의 실패가 다른 회차를 막지 않는다</b> — 회차마다 개별 {@code try-catch} 로
 * 감싸 어떤 예외든(기대한 업무 규칙 위반이든 예상 못 한 버그든) 그 회차의 실패로만 기록하고 다음
 * 회차로 넘어간다. 실패한 회차는 {@code idle} 로 남아(자동 롤백 — {@code RunConfirmationPersistence}
 * 의 트랜잭션이 되돌린다) 실패 횟수에 따라 늘어나는 간격({@link RunConfirmationPolicy#retryDelayAfter})이 지난 뒤 다시 대상이 된다.
 *
 * <p><b>배치 크기 상한</b>은 {@link #BATCH_SIZE} 가 한 틱이 집는 회차 수를 제한하고,
 * <b>동시 실행 상한</b>은 {@link RunConfirmationWorkerPoolConfig} 가 만드는 고정 크기 스레드 풀이
 * 제한한다 — 회차 수가 풀 크기를 넘으면 나머지는 앞 작업이 끝날 때까지 큐에서 대기한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunConfirmationScheduler {

    /**
     * 한 틱이 한 번에 집는 상한 — 상한 없이 전건을 집으면 회차가 몰린 틱 하나가 워커 풀을
     * 오래 붙든다({@code RunRepository.findDueForConfirmation}
     * 의 {@code pageable} 이 이 값을 받는다).
     */
    static final int BATCH_SIZE = 50;

    private final RunRepository runRepository;

    private final RunConfirmationService confirmationService;

    private final ExecutorService runConfirmationExecutor;

    private final Clock clock;

    private final RunConfirmationMetrics metrics;

    /** 예기치 못한 예외(버그·인프라 장애)의 스택은 분당 한 번만 남긴다 — 설정 오류는 스택 없이 한 줄이라 이 제한을 받지 않는다. */
    private final RateLimitedWarn unexpectedFailure = RateLimitedWarn.perMinute(log);

    /** 마지막으로 틱이 정상 반환한 시각 — 한 번도 안 돌았으면 기동 시점부터 잰다(관리자 대시보드 {@code confirm_batch} 칸). */
    private volatile Instant lastSuccessAt = Instant.now();

    /** 마지막 정상 반환 뒤 흐른 시간. */
    public Duration sinceLastSuccess() {
        return Duration.between(lastSuccessAt, Instant.now());
    }

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
     *
     * <p>{@code @SchedulerLock}(DA-12, Ruling 899 J) — 인스턴스 2대가 같은 틱에 같은 회차를 집어 지도 API 를 두 번 부르는 낭비를 막는다.
     * 조건부 UPDATE({@code confirmIfIdle})는 정합성 안전장치로 그대로 둔다. {@code lockAtMostFor} 는 외부 호출이 느린 한 틱(최대 50회차)보다
     * 길고 확정 판정 시각(출발 30분 전)보다 짧게 잡았다.
     */
    @Scheduled(fixedDelayString = "${app.run.confirmation.poll-interval-ms:30000}",
            initialDelayString = "${app.run.confirmation.initial-delay-ms:0}")
    @SchedulerLock(name = "run-confirmation", lockAtMostFor = "PT5M")
    public void confirmDueRuns() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Run> dueRuns = runRepository.findDueForConfirmation(now, LocalDate.now(clock),
                PageRequest.of(0, BATCH_SIZE));

        List<CompletableFuture<Void>> tasks = dueRuns.stream()
                .map(run -> CompletableFuture.runAsync(
                        () -> confirmSafely(run.getId(), run.getConsecutiveFailures()), runConfirmationExecutor))
                .toList();
        CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
        lastSuccessAt = Instant.now();
    }

    /**
     * 회차 1건을 확정하고, 무엇이 됐든 실패를 삼켜 {@code consecutive_failures} 에만 남긴다.
     *
     * <p>{@code BusinessException} 처럼 예상한 실패로 좁히지 않고 {@link Exception} 전체를 잡는다 —
     * 좁히면 예상 못 한 버그(널 참조 등)가 이 스레드를 죽여 {@link CompletableFuture} 가 예외로
     * 완료되고, 그 예외가 {@link #confirmDueRuns} 의 {@code join()} 까지 올라가 <b>이 틱의 나머지
     * 회차 확정 여부와 무관하게 배치 자체가 실패로 끝난다.</b>
     *
     * <p>이 격리 때문에 {@code confirmDueRuns} 자체는 예외를 던지지 않고 정상 반환하므로,
     * {@code @Scheduled} 메서드 전체를 보는 스프링 기본 계측({@code tasks.scheduled.execution})은
     * 이 실패를 절대 못 본다(TECH_DECISIONS §13.1 3행) —
     * 그래서 {@link RunConfirmationMetrics#recordRetryFailure()} 를 여기서 직접 부른다.
     */
    private void confirmSafely(Long runId, int failuresSoFar) {
        try {
            confirmationService.confirmOne(runId);
        } catch (Exception e) {
            Duration delay = RunConfirmationPolicy.retryDelayAfter(failuresSoFar + 1);
            logFailure(runId, e, delay);
            runRepository.recordFailure(runId, OffsetDateTime.now(clock).plus(delay));
            metrics.recordRetryFailure();
        }
    }

    /**
     * 노선 미편성·학원 좌표 미등록 같은 설정 오류({@link BusinessException})는 사람이 고칠 때까지 같은 결과라 스택 없이 한 줄이다 — 오류 코드와
     * 다음 시도까지의 간격이 이 줄에 있다. 그 밖의 예외(버그·인프라 장애)만 스택을 남기되 분당 한 번이다.
     */
    private void logFailure(Long runId, Exception e, Duration delay) {
        if (e instanceof BusinessException business) {
            log.warn("회차 {} 확정 실패({}) — {} 뒤에 재시도한다", runId, business.getErrorCode(), delay);
            return;
        }
        unexpectedFailure.warn(e, "회차 {} 확정 실패 — {} 뒤에 재시도한다", runId, delay);
    }
}
