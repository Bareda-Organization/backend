package src.backend.observability.metrics;

import java.util.Locale;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import src.backend.routing.map.spec.CallerPolicy;

/**
 * 부하 시험 전용 — {@code StubMapRouteClient} 가 프로파일 조건부로 주입하는 지연·429·실패를 센다
 * ({@code IMPLEMENTATION_PLAN §5.5}, F3 L 시나리오 1·4). 기본·시험 프로파일은 주입을 전부 0/비활성으로
 * 두므로(그 클래스 javadoc) 이 카운터들은 부하 프로파일(`app.routing.map.provider=stub` +
 * `app.routing.map.stub.load.*`)이 아니면 절대 오르지 않는다.
 *
 * <p>실 지도 API(Naver) 경로의 {@code resilience4j.*} 지표와 이름을 겹치지 않게 두는 이유는, 이 값이
 * <b>스텁이 흉내 낸 것</b>이지 실제 공급자 신호가 아니기 때문이다 — 같은 이름을 쓰면 관측자가 실
 * 공급자 장애로 오인한다.
 *
 * <p>{@code throttled_total} 만 {@code caller} 태그(F5 목표 4)로 갈린다 — 격벽 상한 초과가
 * {@code BATCH}·{@code ON_DEMAND} 어느 쪽에서 더 걸리는지가 관측 목적이기 때문이고(§5.3
 * on-demand 재측정), timeout·failure_injected 는 이번 목표 범위 밖이라 태그를 추가하지 않는다.
 */
@Component
public class StubMapRouteLoadMetrics {

    private static final String THROTTLED_METRIC = "schoolbus.routing.stub.load.throttled";

    private static final String TIMEOUT_METRIC = "schoolbus.routing.stub.load.timeout";

    private static final String FAILURE_INJECTED_METRIC = "schoolbus.routing.stub.load.failure_injected";

    private static final String CALLER_TAG = "caller";

    private final MeterRegistry registry;

    private final Counter timeoutCounter;

    private final Counter failureInjectedCounter;

    // 생성자에서 즉시 등록한다 — 부하 프로파일이 아니라 이벤트가 한 번도 없어도 /actuator/prometheus
    // 에 이름이 0으로 나와야 관측 스크립트가 "지표가 아예 없다"와 "0건 발생"을 구별할 수 있다.
    public StubMapRouteLoadMetrics(MeterRegistry registry) {
        this.registry = registry;
        // caller 태그값 둘 다 미리 등록한다 — 한쪽 caller 가 이번 실행에서 한 번도 안 걸려도
        // 0으로 나와야 위와 같은 이유로 "지표 부재"와 "0건 발생"이 구별된다.
        for (CallerPolicy caller : CallerPolicy.values()) {
            throttledCounter(caller);
        }
        this.timeoutCounter = Counter.builder(TIMEOUT_METRIC)
                .description("주입 지연이 호출자 타임아웃(RoadRouteRequest.timeout)을 넘겨 시간 초과를 모사한 횟수")
                .register(registry);
        this.failureInjectedCounter = Counter.builder(FAILURE_INJECTED_METRIC)
                .description("failure-rate 로 뽑힌 무작위 실패 모사 횟수 — ON_DEMAND 는 서킷 개방으로, BATCH 는 폴백으로 이어진다")
                .register(registry);
    }

    public void recordThrottled(CallerPolicy caller) {
        throttledCounter(caller).increment();
    }

    public void recordTimeout() {
        timeoutCounter.increment();
    }

    public void recordFailureInjected() {
        failureInjectedCounter.increment();
    }

    // MeterRegistry.register 는 같은 이름+태그 조합이면 기존 Counter 를 그대로 돌려주므로
    // (Micrometer Id 동일성), 생성자 사전 등록과 record 시점 호출이 같은 인스턴스를 공유한다.
    private Counter throttledCounter(CallerPolicy caller) {
        return Counter.builder(THROTTLED_METRIC)
                .description("동시 처리 상한(app.routing.map.stub.load.max-concurrent) 초과로 격벽 거부를 모사한 횟수")
                .tag(CALLER_TAG, caller.name().toLowerCase(Locale.ROOT))
                .register(registry);
    }
}
