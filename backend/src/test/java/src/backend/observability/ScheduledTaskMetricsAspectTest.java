package src.backend.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * AOP 프록시가 실제 {@code @Scheduled} 빈에 걸리는지 확인한다.
 * {@code LocationSimulationScheduler} 는 3초 주기라 짧게 기다리면 최소 한 번은 돈다.
 */
@SpringBootTest
class ScheduledTaskMetricsAspectTest {

    @Autowired
    private MeterRegistry registry;

    @Test
    void aspect_recordsGaugeForRealScheduledBean() {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(registry.find("schoolbus.scheduler.last.success.age")
                        .tag("scheduler", "location-simulation").gauge())
                        .as("3초 주기 스케줄러가 돌았는데 게이지가 없으면 AOP 프록시가 걸리지 않은 것이다")
                        .isNotNull());
    }
}
