package src.backend.global.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import src.backend.global.cache.CacheEvictionRelay;
import testsupport.redis.RedisTestContainerBase;

/**
 * 권한이 바뀐 계정의 세션 닫기(4500)는 그 세션을 쥔 인스턴스가 어디든 닫혀야 한다 — 이벤트를 받은 인스턴스가 자기 세션만 닫으면 다른 인스턴스에 붙은 보호자의 연결이 계속 다른 아이의 위치를 받는다.
 * 인스턴스 하나 = 세션 보관소(여기서는 닫은 계정을 기록하는 대역) + 비움 중계 + 자기 Redis 연결이다.
 */
class AccountSessionsRevokeRelayTest extends RedisTestContainerBase {

    private final String channelSuffix = ":" + UUID.randomUUID();
    private final List<Instance> instances = new CopyOnWriteArrayList<>();

    @AfterEach
    void close() throws Exception {
        for (Instance instance : instances) {
            instance.close();
        }
    }

    @Test
    @DisplayName("한 인스턴스가 낸 세션 닫기 이벤트는 모든 인스턴스가 자기 세션에 적용한다")
    void 세션_닫기가_모든_인스턴스에_적용된다() {
        Instance first = new Instance();
        Instance second = new Instance();
        awaitBothSubscribed(first, second);

        first.listener.revoke(new AccountSessionsRevokeEvent(Set.of(11L, 12L)));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(second.revoked).as("다른 인스턴스가 닫은 계정").containsExactlyInAnyOrder(11L, 12L));
        assertThat(first.revoked).as("발행한 인스턴스는 자기 세션을 바로 닫는다").containsExactlyInAnyOrder(11L, 12L);
    }

    @Test
    @DisplayName("자기가 낸 신호가 Redis 를 돌아와도 같은 세션 닫기를 한 번 더 하지 않는다")
    void 자기_신호는_다시_처리하지_않는다() {
        Instance first = new Instance();
        Instance second = new Instance();
        awaitBothSubscribed(first, second);

        first.listener.revoke(new AccountSessionsRevokeEvent(Set.of(21L)));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(second.revoked).containsExactly(21L));
        // 같은 채널 · 같은 연결에서 뒤에 낸 다른 신호를 첫 인스턴스가 받았다면, 앞서 돌아온 자기 신호도 이미 처리를 마친 것이다
        first.foreignSignal("probe", 99);
        await().atMost(Duration.ofSeconds(5)).until(() -> first.probes.contains(99L));

        assertThat(first.revoked).as("발행한 인스턴스의 닫기 횟수").containsExactly(21L);
    }

    @Test
    @DisplayName("Redis 에 신호를 못 내도 이 인스턴스의 세션은 닫힌다")
    void Redis_장애에도_로컬_세션은_닫힌다() {
        OpenWebSocketSessions openSessions = mock(OpenWebSocketSessions.class);
        StringRedisTemplate brokenRedis = mock(StringRedisTemplate.class);
        doThrow(new IllegalStateException("redis down")).when(brokenRedis).convertAndSend(anyString(), anyString());
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        CacheEvictionRelay relay = new CacheEvictionRelay(brokenRedis, mock(RedisMessageListenerContainer.class),
                meters, channelSuffix);
        AccountSessionsRevokeListener listener = new AccountSessionsRevokeListener(openSessions, relay);

        ListAppender<ILoggingEvent> logs = 로그를_모은다(CacheEvictionRelay.class);
        try {
            listener.revoke(new AccountSessionsRevokeEvent(Set.of(31L)));
        } finally {
            로그_수집을_끝낸다(CacheEvictionRelay.class, logs);
        }

        verify(openSessions).revoke(Set.of(31L));
        assertThat(meters.counter("schoolbus.cache.evict.relay.failed", "cache", CacheEvictionRelay.SESSIONS_REVOKE).count())
                .as("발행 실패는 지표로 센다").isEqualTo(1.0);
        assertThat(경고(logs)).as("신호를 못 낸 사실은 WARN 으로 남는다").singleElement()
                .satisfies(message -> assertThat(message).contains("캐시 비움 신호 발행 실패"));
    }

    @Test
    @DisplayName("이 인스턴스의 세션 닫기가 실패해도 다른 인스턴스에 알리고 예외는 밖으로 나가지 않는다")
    void 로컬_닫기_실패에도_신호는_나간다() {
        OpenWebSocketSessions openSessions = mock(OpenWebSocketSessions.class);
        org.mockito.Mockito.when(openSessions.revoke(org.mockito.ArgumentMatchers.<Collection<Long>>any()))
                .thenThrow(new IllegalStateException("close failed"));
        CacheEvictionRelay relay = mock(CacheEvictionRelay.class);
        AccountSessionsRevokeListener listener = new AccountSessionsRevokeListener(openSessions, relay);

        ListAppender<ILoggingEvent> logs = 로그를_모은다(AccountSessionsRevokeListener.class);
        try {
            org.assertj.core.api.Assertions.assertThatCode(() -> listener.revoke(new AccountSessionsRevokeEvent(Set.of(41L))))
                    .doesNotThrowAnyException();
        } finally {
            로그_수집을_끝낸다(AccountSessionsRevokeListener.class, logs);
        }

        verify(relay).publish(CacheEvictionRelay.SESSIONS_REVOKE, 41L);
        assertThat(경고(logs)).as("이 인스턴스의 세션을 닫지 못한 사실은 WARN 으로 남는다").singleElement()
                .satisfies(message -> assertThat(message).contains("세션을 이 인스턴스에서 닫지 못했다"));
    }

    private static ListAppender<ILoggingEvent> 로그를_모은다(Class<?> source) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(source)).addAppender(appender);
        return appender;
    }

    private static void 로그_수집을_끝낸다(Class<?> source, ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(source)).detachAppender(appender);
        appender.stop();
    }

    private static List<String> 경고(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** 두 인스턴스의 구독이 모두 성립할 때까지 다른 인스턴스 이름의 탐침을 보낸다 — 구독이 성립하기 전의 메시지는 Redis 가 버린다. */
    private void awaitBothSubscribed(Instance first, Instance second) {
        first.relay.register("probe", key -> first.probes.add(key));
        second.relay.register("probe", key -> second.probes.add(key));
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).until(() -> {
            first.foreignSignal("probe", 0);
            return !first.probes.isEmpty() && !second.probes.isEmpty();
        });
    }

    private final class Instance {
        final Set<Long> probes = ConcurrentHashMap.newKeySet();
        final List<Long> revoked = new CopyOnWriteArrayList<>();
        final LettuceConnectionFactory factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        final RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        final StringRedisTemplate redis;
        final CacheEvictionRelay relay;
        final AccountSessionsRevokeListener listener;

        Instance() {
            factory.afterPropertiesSet();
            container.setConnectionFactory(factory);
            container.afterPropertiesSet();
            container.start();
            redis = new StringRedisTemplate(factory);
            relay = new CacheEvictionRelay(redis, container, new SimpleMeterRegistry(), channelSuffix);
            OpenWebSocketSessions openSessions = mock(OpenWebSocketSessions.class);
            org.mockito.Mockito.when(openSessions.revoke(org.mockito.ArgumentMatchers.<Collection<Long>>any()))
                    .thenAnswer(call -> {
                        revoked.addAll(call.<Collection<Long>>getArgument(0));
                        return 1;
                    });
            listener = new AccountSessionsRevokeListener(openSessions, relay);
            instances.add(this);
        }

        /** 같은 채널에 발행자 이름이 다른 신호를 낸다 — 이 인스턴스가 낸 신호가 아니므로 모든 인스턴스가 처리한다. */
        void foreignSignal(String cache, long key) {
            redis.convertAndSend("schoolbus:relay:cache-evict" + channelSuffix, "probe-origin|" + cache + "|" + key);
        }

        void close() throws Exception {
            container.destroy();
            factory.destroy();
        }
    }
}
