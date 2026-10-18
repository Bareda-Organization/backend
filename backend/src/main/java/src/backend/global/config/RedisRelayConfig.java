package src.backend.global.config;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import src.backend.global.redis.FailSoftRedisMessageListenerContainer;
import src.backend.global.redis.RedisSubscriptionRetrier;

/**
 * 인스턴스 사이 중계(방송 · 캐시 비움 신호)가 함께 쓰는 Redis 구독 컨테이너. 받은 메시지는 스레드 1개가 순서대로 처리한다 — Redis 에 들어간 순서가 구독자에게 나가는 순서가 돼야
 * 클라이언트가 오래된 메시지를 버리는 기준(시각)과 어긋나지 않는다. 큐가 차면 구독을 읽는 스레드가 직접 처리해 메시지를 잃지 않고 읽기만 느려진다.
 */
@Configuration(proxyBeanMethods = false)
public class RedisRelayConfig {

    /** 받은 메시지 처리 대기 상한 — 무한 큐는 처리가 밀릴 때 힙에 쌓인다. */
    static final int RECEIVE_QUEUE_CAPACITY = 10_000;

    /** 받은 메시지를 처리하는 실행기 — 일반 후보에서 뺀다({@code inboundTaskExecutor} 와 같은 이유). */
    @Bean(defaultCandidate = false)
    ThreadPoolTaskExecutor relayReceiveExecutor() {
        ThreadPoolTaskExecutor receiver = new ThreadPoolTaskExecutor();
        receiver.setThreadNamePrefix("relay-receive-");
        receiver.setCorePoolSize(1);
        receiver.setMaxPoolSize(1);
        receiver.setQueueCapacity(RECEIVE_QUEUE_CAPACITY);
        receiver.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        receiver.initialize();
        return receiver;
    }

    /** 빈 이름을 Boot 가 만드는 기본 컨테이너와 같게 두어 그쪽이 물러나고 이 컨테이너 하나만 남는다. */
    @Bean
    FailSoftRedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory connectionFactory,
            @Qualifier("relayReceiveExecutor") ThreadPoolTaskExecutor receiver) {
        FailSoftRedisMessageListenerContainer container = new FailSoftRedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setTaskExecutor(receiver);
        return container;
    }

    /** Redis 에 못 붙어 서지 못한 구독을 다시 건다 — Redis 가 멈춘 채 기동해도 애플리케이션은 뜬다. */
    @Bean
    RedisSubscriptionRetrier redisSubscriptionRetrier(FailSoftRedisMessageListenerContainer container) {
        return new RedisSubscriptionRetrier(container);
    }
}
