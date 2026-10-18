package src.backend.global.redis;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * Boot 가 만드는 {@link StringRedisTemplate} 자리에 {@link GuardedStringRedisTemplate} 을 둔다 — 같은 타입의 빈이 먼저 있으면 Boot 의 기본 빈이 물러난다.
 * 서킷 설정은 {@code application.yml} 의 {@code resilience4j.circuitbreaker.instances.redis}. 서킷을 기동 때 만들어 두어 상태 지표가 처음부터 나온다.
 */
@Configuration(proxyBeanMethods = false)
public class RedisGuardConfig {

    static final String CIRCUIT = "redis";

    @Bean
    StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory, CircuitBreakerRegistry circuitBreakers) {
        return new GuardedStringRedisTemplate(connectionFactory, circuitBreakers.circuitBreaker(CIRCUIT));
    }
}
