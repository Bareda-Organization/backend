package src.backend.observability.metrics;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 알림 발송이 상한을 소진해 {@code failed} 로 굳는 건수를 센다({@code TECH_DECISIONS §13.1}).
 *
 * <p>즉시 발송(1회차)은 {@code NotificationRetryPolicy.MAX_ATTEMPTS} 조건상 절대 이 상태에 닿지
 * 않는다 — {@code failed} 는 {@code NotificationOutboxWorker} 의 재시도가 전부 소진됐을 때만
 * 나온다. 실제 상태 전이는 {@code NotificationDispatcher}(범위 밖) 안에 있어 반환값이 없으므로,
 * 워커가 재조회한 결과를 보고 이 카운터를 올린다.
 */
@Component
public class NotificationPushMetrics {

    private static final String FAILURE_METRIC = "schoolbus.notification.push.failures";

    private final Counter failureCounter;

    // 생성자에서 즉시 등록한다 — 실패가 한 번도 없는 기동 직후에도 이름이 노출돼야 한다.
    public NotificationPushMetrics(MeterRegistry registry) {
        this.failureCounter = Counter.builder(FAILURE_METRIC)
                .description("재시도를 전부 소진해 failed 로 굳은 알림 발송 건수")
                .register(registry);
    }

    public void recordFailure() {
        failureCounter.increment();
    }
}
