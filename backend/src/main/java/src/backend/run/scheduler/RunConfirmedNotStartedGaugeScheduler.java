package src.backend.run.scheduler;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicLong;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import src.backend.global.policy.PolicyConstants;
import src.backend.run.repository.RunRepository;

/**
 * {@code schoolbus.run.confirmed.not_started} 게이지를 갱신한다 — 확정됐는데 운행 시작 가능 시간창(출발 시각 + 10분)이 닫히도록 시작하지 않은
 * 회차 수다. 확정 뒤에는 어떤 배치도 그 회차를 다시 집지 않아, 노선을 받은 기사가 출발하지 않은 채 남아도 이 신호 말고는 드러나지 않는다.
 * 자동으로 닫지 않는다 — 사람이 기사에게 연락해 처리하는 회차다. 쓰기 없는 순수 조회라 {@code @SchedulerLock} 은 같은 집계를 인스턴스마다
 * 반복하지 않게 하는 용도뿐이다({@code RunUnconfirmedGaugeScheduler} 와 같은 관례).
 */
@Component
public class RunConfirmedNotStartedGaugeScheduler {

    private static final String METRIC = "schoolbus.run.confirmed.not_started";

    private final RunRepository runRepository;

    private final Clock clock;

    private final AtomicLong value = new AtomicLong(0);

    // 생성자에서 즉시 등록한다 — 갱신이 한 번도 안 돈 기동 직후에도 이름이 0 으로 노출돼야 한다.
    public RunConfirmedNotStartedGaugeScheduler(RunRepository runRepository, Clock clock, MeterRegistry registry) {
        this.runRepository = runRepository;
        this.clock = clock;
        Gauge.builder(METRIC, value, AtomicLong::get)
                .description("확정됐지만 시작 가능 시간창(출발 + 10분)이 닫히도록 시작하지 않은 회차 수")
                .register(registry);
    }

    /** 시간창이 닫힌 확정 회차 수를 세어 게이지에 반영한다. 폴링 주기를 설정으로 받는 이유는 테스트에서 배경 실행을 미루기 위함이다. */
    @Scheduled(fixedDelayString = "${app.observability.run-confirmed-not-started.poll-interval-ms:30000}",
            initialDelayString = "${app.observability.run-confirmed-not-started.initial-delay-ms:0}")
    @SchedulerLock(name = "run-confirmed-not-started-gauge", lockAtMostFor = "PT2M")
    public void refresh() {
        OffsetDateTime threshold = OffsetDateTime.now(clock).minus(PolicyConstants.START_WINDOW);
        value.set(runRepository.countConfirmedNotStarted(threshold, LocalDate.now(clock)));
    }
}
