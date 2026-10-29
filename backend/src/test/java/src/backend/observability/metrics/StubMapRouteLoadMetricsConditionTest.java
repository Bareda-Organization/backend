package src.backend.observability.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** 부하 시험 전용 지표는 스텁 지도 클라이언트를 켠 서버에서만 등록된다 — 운영에 항상 0 인 시리즈가 노출되지 않는다. */
class StubMapRouteLoadMetricsConditionTest {

    @Configuration(proxyBeanMethods = false)
    static class Registry {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Registry.class, StubMapRouteLoadMetrics.class);

    @Test
    @DisplayName("provider=stub 이면 등록된다")
    void stub_이면_등록된다() {
        runner.withPropertyValues("app.routing.map.provider=stub")
                .run(context -> assertThat(context).hasSingleBean(StubMapRouteLoadMetrics.class));
    }

    @Test
    @DisplayName("provider=naver 이거나 설정이 없으면 등록되지 않는다")
    void naver_이거나_설정이_없으면_등록되지_않는다() {
        runner.withPropertyValues("app.routing.map.provider=naver")
                .run(context -> assertThat(context).doesNotHaveBean(StubMapRouteLoadMetrics.class));
        // 시험 JVM 은 이 속성을 stub 으로 심으므로 비워서 "설정이 없는" 서버를 만든다
        runner.withSystemProperties("app.routing.map.provider=")
                .run(context -> assertThat(context).doesNotHaveBean(StubMapRouteLoadMetrics.class));
    }
}
