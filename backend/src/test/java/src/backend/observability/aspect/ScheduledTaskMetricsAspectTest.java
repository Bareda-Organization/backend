package src.backend.observability.aspect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * AOP 프록시가 실제 {@code @Scheduled} 빈에 걸리는지 확인한다.
 *
 * <p>실제 도메인 스케줄러가 아직 하나도 없어(Phase 0), 부착 대상은 이 테스트 전용 더미
 * {@code @Scheduled} 빈({@link DummyScheduler})을 {@link Import} 로 직접 등록해 만든다.
 */
@SpringBootTest
@Import(ScheduledTaskMetricsAspectTest.DummySchedulerConfig.class)
class ScheduledTaskMetricsAspectTest {

    @Autowired
    private MeterRegistry registry;

    @Test
    void aspect_recordsGaugeForRealScheduledBean() {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(registry.find("schoolbus.scheduler.last.success.age")
                        .tag("scheduler", "dummy").gauge())
                        .as("1초 주기 더미 스케줄러가 돌았는데 게이지가 없으면 AOP 프록시가 걸리지 않은 것이다")
                        .isNotNull());
    }

    /** 더미 스케줄러 빈을 테스트 컨텍스트에 등록하는 전용 설정. */
    static class DummySchedulerConfig {

        @Bean
        DummyScheduler dummyScheduler() {
            return new DummyScheduler();
        }
    }
}
