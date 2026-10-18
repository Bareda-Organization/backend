package src.backend.location.infrastructure;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.springframework.boot.data.redis.autoconfigure.ClientResourcesBuilderCustomizer;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.resource.Delay;

/**
 * Redis 클라이언트(Lettuce) 동작 설정 — 연결이 끊긴 동안 들어온 명령을 쌓아 두지 않고 즉시 거절한다(BR-166, W12-01).
 * Lettuce 기본값은 재연결까지 명령을 받아 두었다가 시간 상한까지 기다려, 그동안 호출자가 쥔 DB 커넥션이 함께
 * 묶인다. 시간 상한 자체는 {@code application.yml} 의 {@code spring.data.redis.timeout}.
 *
 * <p>Redis 를 쓰는 곳이 이 모듈의 회차 최신 좌표 하나라 여기 둔다. ⚠ 직렬화기 빈을 여기 두지
 * 않는다 — 옛 {@code RedisConfig} 의 다형 직렬화 빈이 좌표 값 형식을 깨뜨린 사고는
 * {@code RunPositionRedisValue} 자바독.
 */
@Configuration(proxyBeanMethods = false)
public class RedisClientConfig {

    /** Boot 가 만든 {@link ClientOptions}(연결 시간 상한 포함)에 끊김 중 거절만 더한다. */
    @Bean
    LettuceClientOptionsBuilderCustomizer rejectCommandsWhileDisconnected() {
        return builder -> builder.disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
    }

    /**
     * 재접속 대기에 상한(2초)을 둔다 — Lettuce 기본값은 시도 횟수에 따라 지수로 늘어 30초까지 간다. 오래 멈췄던 Redis 가 돌아와도 명령 연결과 중계 구독 연결이 그 대기만큼
     * 늦게 돌아와 대체 경로로 도는 시간이 길어진다(Ruling 919). 100ms 에서 시작해 2배씩 늘다 2초에서 멈춘다.
     */
    @Bean
    ClientResourcesBuilderCustomizer boundReconnectDelay() {
        return builder -> builder.reconnectDelay(
                Delay.exponential(Duration.ofMillis(100), Duration.ofSeconds(2), 2, TimeUnit.MILLISECONDS));
    }
}
