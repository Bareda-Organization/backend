package src.backend.observability.metrics;

import java.time.Duration;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * 확정 배치의 도래→완료 지연과 회차 단위 실패를 계측한다(RTE-08, Phase 7 목표 8, {@code ARCHITECTURE
 * §9.4} 끝 문장 · TECH_DECISIONS §13.1 3행).
 *
 * <p>재는 값은 <b>회차의 판정 시각({@code run.confirm_at})부터 실제 확정 완료 시각까지의 차</b>다 — 배치
 * 1회 실행에 걸린 시간(처리 자체의 소요)이 아니다. 처리 한 건이 매번 수십 ms 로 끝나도 도래분이
 * {@code RunConfirmationScheduler#BATCH_SIZE} 를 넘어 계속 밀리면 확정이 실제 판정 시각보다 한참 뒤에
 * 일어나는데, 그동안 실행 시간은 변하지 않는다 — 이 값만이 그 밀림을 드러낸다.
 *
 * <p>이 값이 계속 늘면 배치 크기가 아니라 <b>워커 수·인스턴스</b>를 늘려야 한다는 신호다(`§9.4`).
 *
 * <p>태그에 학원 id·회차 id 를 붙이지 않는다({@link PipelineMetrics} 와 같은 근거) — 시계열이 대상
 * 수만큼 갈라지는 것과 개인 식별 정보가 지표에 섞이는 것을 막는다.
 *
 * <p>{@code schoolbus.run.confirmation.retry_failures} 는 {@code schoolbus.scheduler.failures}
 * ({@link src.backend.observability.aspect.ScheduledTaskMetricsAspect})와 다른 신호다. 저 카운터는
 * {@code @Scheduled} 메서드 자체가 예외를 던질 때만 오르는데, {@code RunConfirmationScheduler} 는
 * 회차 단위로 {@code try-catch} 를 격리해(목표 4) 개별 실패를 삼키고 {@code consecutive_failures} 에만
 * 남긴다 — TECH_DECISIONS §13.1 3행이 정확히 "회차 단위로 격리되므로 실패가 로그에만 남고 응답에
 * 미노출"이라 지적하는 지점이다. 즉 이 카운터가 없으면 회차별 확정 실패는 Prometheus 에 영원히
 * 보이지 않는다.
 */
@Component
public class RunConfirmationMetrics {

    private static final String LAG_METRIC = "schoolbus.run.confirmation.lag";

    private static final String RETRY_FAILURE_METRIC = "schoolbus.run.confirmation.retry_failures";

    private final Timer lagTimer;

    private final Counter retryFailureCounter;

    // 생성자에서 즉시 등록한다 — recordLag 를 한 번도 안 부른 상태(회차가 도래하지 않은 기동 직후)에도
    // /actuator/prometheus 에 이름이 나와야 한다(관측 시험이 이벤트 없이 이름 존재를 확인한다).
    public RunConfirmationMetrics(MeterRegistry registry) {
        this.lagTimer = Timer.builder(LAG_METRIC)
                .description("배치 도래(confirm_at)부터 확정 완료까지의 지연. 늘면 워커 수·인스턴스 증설 신호")
                .register(registry);
        this.retryFailureCounter = Counter.builder(RETRY_FAILURE_METRIC)
                .description("회차 단위로 격리돼 로그에만 남던 확정 실패를 센다 — consecutive_failures 증가와 짝")
                .register(registry);
    }

    /**
     * 회차 1건이 확정될 때(또는 동시 확정 경합에서 진 시도가 조용히 반환될 때) 도래→완료 지연을
     * 기록한다. 실패해 {@code idle} 로 되돌아간 시도는 호출부({@code RunConfirmationService#confirmOne})가
     * 이 메서드를 아예 부르지 않는다 — 그 지연은 유실되지 않고, 다음 틱에 같은 회차가 마침내 성공할
     * 때 <b>최초 판정 시각부터 누적된 값</b>으로 잡힌다({@code run.confirm_at} 이 재시도로 바뀌지
     * 않기 때문).
     */
    public void recordLag(Duration lag) {
        lagTimer.record(lag);
    }

    /**
     * 회차 1건의 확정 시도가 실패해 {@code idle} 로 되돌아갈 때마다({@code RunConfirmationScheduler
     * #confirmSafely}) 부른다 — {@code runRepository.recordFailure(runId)} 와 항상 짝으로 호출된다.
     */
    public void recordRetryFailure() {
        retryFailureCounter.increment();
    }
}
