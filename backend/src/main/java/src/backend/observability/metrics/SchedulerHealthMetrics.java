package src.backend.observability.metrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * {@code @Scheduled} 작업이 마지막으로 성공한 뒤 흐른 시간과 실패 누적을 보관한다.
 *
 * <p>이 프로젝트의 스케줄러 4개는 예외를 잡아 로그만 남기고 계속 진행한다 — 판정 로직이 실패해도
 * 앱은 정상 기동 상태를 유지하고, 알림이 나가지 않는 것으로만 뒤늦게 드러난다. 특히
 * {@code SosEscalationScheduler} 가 멈추면 3분 미확인 SOS 가 에스컬레이션되지 않는다.
 * "마지막 성공 이후 경과"를 게이지로 두면 그 정지를 대시보드에서 볼 수 있다.
 *
 * <p>이 클래스는 값만 보관한다. 언제 호출할지는 {@code ScheduledTaskMetricsAspect} 가 정하며,
 * 스케줄러 코드는 이 계측의 존재를 모른다.
 *
 * <p>시계를 생성자로 주입받는 이유는 테스트에서 시간 흐름을 조작하기 위해서다.
 */
@Component
public class SchedulerHealthMetrics {

    private static final String AGE_METRIC = "schoolbus.scheduler.last.success.age";
    private static final String FAILURE_METRIC = "schoolbus.scheduler.failures";

    private final MeterRegistry registry;
    private final LongSupplier clockMillis;
    private final Map<String, AtomicLong> lastSuccessMillis = new ConcurrentHashMap<>();

    // 생성자가 둘이라 Spring이 어느 쪽을 주입 대상으로 쓸지 판단할 수 없다. Spring은
    // getDeclaredConstructors()로 접근제어자와 무관하게 모든 생성자를 보므로 2-인자 생성자를
    // package-private으로 둬도 모호성은 그대로 남는다 — "no default constructor found"로
    // 기동 실패하니 @Autowired로 실사용 생성자를 명시한다.
    @Autowired
    public SchedulerHealthMetrics(MeterRegistry registry) {
        this(registry, System::currentTimeMillis);
    }

    // 테스트 전용 시계 주입 생성자. 이 저장소는 테스트 패키지를 대상 클래스 패키지와 1:1로 맞추는
    // 관행이라(bus/command, route/query 등과 동일) 테스트가 같은 패키지(src.backend.observability.metrics)에
    // 있으므로 package-private으로 충분하다 — 공개 API 표면을 넓힐 필요가 없다.
    SchedulerHealthMetrics(MeterRegistry registry, LongSupplier clockMillis) {
        this.registry = registry;
        this.clockMillis = clockMillis;
    }

    /** 한 주기가 정상 완료됨. 경과 게이지가 0 으로 돌아간다. */
    public void recordSuccess(String scheduler) {
        anchor(scheduler).set(clockMillis.getAsLong());
    }

    /** 한 주기가 실패함. 실패 수만 올리고 성공 시각은 갱신하지 않는다 — 경과가 계속 늘어야 정지가 드러난다. */
    public void recordFailure(String scheduler) {
        anchor(scheduler);
        registry.counter(FAILURE_METRIC, "scheduler", scheduler).increment();
    }

    /**
     * 아직 한 번도 돌지 않은 스케줄러의 게이지·카운터를 0으로 미리 등록한다.
     *
     * <p>호출자는 {@code ScheduledTaskMetricsAspect} 뿐이며, 그쪽이 기동 시점에 등록된
     * {@code @Scheduled} 메서드 전부를 순회하며 이름을 넘긴다 — 이름 목록을 여기서 상수로
     * 들고 있지 않으려는 것이 목적이라(클래스 상단 주석), 이 메서드 자체는 이름을 모른 채
     * 등록만 한다.
     */
    public void preRegister(String scheduler) {
        anchor(scheduler);
        registry.counter(FAILURE_METRIC, "scheduler", scheduler);
    }

    private AtomicLong anchor(String scheduler) {
        return lastSuccessMillis.computeIfAbsent(scheduler, name -> {
            AtomicLong value = new AtomicLong(clockMillis.getAsLong());
            Gauge.builder(AGE_METRIC, value, v -> (clockMillis.getAsLong() - v.get()) / 1000.0d)
                    .tag("scheduler", name)
                    .description("마지막 성공 이후 경과 시간(초). 값이 계속 늘면 그 스케줄러가 멈춘 것이다")
                    .baseUnit("seconds")
                    .register(registry);
            return value;
        });
    }
}
