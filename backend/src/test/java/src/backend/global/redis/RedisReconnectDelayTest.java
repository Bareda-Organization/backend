package src.backend.global.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.lettuce.core.resource.ClientResources;

import src.backend.location.infrastructure.RedisClientConfig;

/**
 * 오래 멈췄던 Redis 가 돌아와도 연결이 곧바로 다시 서도록 재접속 대기에 상한(2초)을 둔다 — Lettuce 기본값은 시도 횟수에 따라 지수로 늘어 30초까지 가서, 오래 멈춘 뒤에는
 * Redis 가 돌아와도 중계 구독과 명령 연결이 그 대기만큼 늦게 돌아온다. 상한이 있으면 어떤 긴 장애 뒤에도 복구 지연이 그 안이다.
 */
class RedisReconnectDelayTest {

    private static final Duration RECONNECT_DELAY_LIMIT = Duration.ofSeconds(2);

    @Test
    @DisplayName("재접속 대기는 시도를 아무리 많이 해도 2초를 넘지 않는다")
    void 재접속_대기에_상한이_있다() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class))
                .withUserConfiguration(RedisClientConfig.class)
                .run(context -> {
                    ClientResources resources = context.getBean(ClientResources.class);

                    for (long attempt : new long[] {1, 5, 15, 30, 100}) {
                        assertThat(resources.reconnectDelay().createDelay(attempt))
                                .as("%d번째 재접속 시도 앞의 대기".formatted(attempt)).isLessThanOrEqualTo(RECONNECT_DELAY_LIMIT);
                    }
                });
    }
}
