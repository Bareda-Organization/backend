package testsupport.redis;

import java.time.Clock;
import java.time.Duration;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.github.resilience4j.springboot.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;

import src.backend.global.config.RedisRelayConfig;
import src.backend.global.redis.RedisGuardConfig;
import src.backend.location.infrastructure.RedisClientConfig;

/**
 * 실제 운영 빈 조합(Redis 연결 · 연결 끊김 거절 · {@code redis} 서킷이 감싼 템플릿 · 구독 컨테이너)을 {@link OutageRedis} 에 붙여 띄우는 도우미 —
 * {@code application.yml} 을 그대로 읽어 명령 시간 상한(500ms) · 서킷 설정이 운영과 같다. Redis 장애 대체 경로 시험이 이 위에서 사용처 클래스를 직접 만든다.
 */
public final class OutageRedisContext {

    /** 얼린 Redis 앞에서 서킷이 열리기 전까지 실패하는 호출 수 — 이만큼 호출한 뒤에는 Redis 를 부르지 않는다({@code application.yml} 의 최소 표본). */
    public static final int CALLS_TO_OPEN = 5;

    /** 서킷이 열리기 전 한 호출이 쓸 수 있는 시간 — 명령 시간 상한 500ms 에 여유를 더한 값. */
    public static final Duration BOUNDED_CALL = Duration.ofMillis(1500);

    /** 서킷이 열린 뒤 한 호출이 쓸 수 있는 시간 — Redis 를 기다리지 않으므로 명령 시간 상한에 한참 못 미친다. */
    public static final Duration SKIPPED_CALL = Duration.ofMillis(200);

    private OutageRedisContext() {
    }

    public static ApplicationContextRunner runner(OutageRedis redis) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class, CircuitBreakerAutoConfiguration.class))
                .withUserConfiguration(RedisClientConfig.class, RedisGuardConfig.class, RedisRelayConfig.class)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
                .withBean(Clock.class, Clock::systemUTC)
                .withPropertyValues("spring.data.redis.host=" + redis.host(), "spring.data.redis.port=" + redis.port());
    }

    /** {@code call} 한 번이 걸린 시간 — 던진 예외는 삼킨다(실패 시간을 재는 것이 목적이다). */
    public static Duration timeOf(Runnable call) {
        long start = System.nanoTime();
        try {
            call.run();
        } catch (RuntimeException ignored) {
            // 실패 시간을 재는 것이 목적이다
        }
        return Duration.ofNanos(System.nanoTime() - start);
    }
}
