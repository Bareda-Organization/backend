package src.backend.location.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import src.backend.global.cache.CacheEvictionRelay;
import src.backend.global.location.RunRiderDirectory;
import src.backend.global.location.RunRidersChangedEvent;
import src.backend.global.websocket.BroadcastDelivery;
import src.backend.global.websocket.WebSocketBroadcastGateway;
import src.backend.global.websocket.WebSocketDestinations;
import src.backend.location.event.RunPositionReceivedEvent;
import src.backend.location.infrastructure.RunBroadcastCache;
import testsupport.redis.RedisTestContainerBase;

/**
 * S-7 — 인스턴스 A 에서 학생이 결석 처리되면 B 의 위치 방송이 그 학생 채널로 나가지 않는다(Ruling 900 D). B 는 회차의 학생 목록을 캐시에 들고 있어, 비움 신호가 안 닿으면
 * 결석한 학생의 학부모 지도에 다음 위치가 계속 나간다. 인스턴스 하나 = 캐시 + 비움 리스너 + 위치 방송 리스너 + 비움 중계, 같은 Redis 를 본다.
 */
class RunBroadcastCacheRelayTest extends RedisTestContainerBase {

    private static final long RUN_ID = 10L;
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-18T09:00:00+09:00");

    private final String channelSuffix = ":" + UUID.randomUUID();
    private final List<Instance> instances = new CopyOnWriteArrayList<>();

    @AfterEach
    void closeAll() throws Exception {
        for (Instance instance : instances) {
            instance.close();
        }
    }

    @Test
    @DisplayName("A 에서 결석 처리 신호를 내면 B 의 다음 위치 방송은 그 학생 채널로 0건이다")
    void 결석_신호는_다른_인스턴스의_학생_목록을_비운다() throws Exception {
        Instance a = newInstance();
        Instance b = newInstance();
        awaitSubscribed(a, b);
        given(b.directory.studentIdsExcludingAbsent(RUN_ID)).willReturn(List.of(1L, 2L));
        assertThat(b.broadcastOnce()).as("결석 처리 전 B 의 학생 채널").contains(WebSocketDestinations.studentRun(1L));

        // 학생 1 이 결석 처리돼 DB 가 바뀐 뒤: A 의 커밋 뒤 리스너가 신호를 낸다
        given(b.directory.studentIdsExcludingAbsent(RUN_ID)).willReturn(List.of(2L));
        a.evictionListener.onRidersChanged(new RunRidersChangedEvent(RUN_ID));

        await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(20)).untilAsserted(() ->
                assertThat(b.broadcastOnce()).as("결석 처리 뒤 B 의 학생 채널")
                        .doesNotContain(WebSocketDestinations.studentRun(1L)).contains(WebSocketDestinations.studentRun(2L)));
    }

    private void awaitSubscribed(Instance a, Instance b) {
        AtomicLong probesOnA = new AtomicLong();
        AtomicLong probesOnB = new AtomicLong();
        a.relay.register("probe", key -> probesOnA.incrementAndGet());
        b.relay.register("probe", key -> probesOnB.incrementAndGet());
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).until(() -> {
            a.relay.publish("probe", 0);
            b.relay.publish("probe", 0);
            return probesOnA.get() > 0 && probesOnB.get() > 0;
        });
    }

    private Instance newInstance() {
        Instance instance = new Instance();
        instances.add(instance);
        return instance;
    }

    private final class Instance {
        final LettuceConnectionFactory factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        final RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        final CacheEvictionRelay relay;
        final RunRiderDirectory directory = mock(RunRiderDirectory.class);
        final WebSocketBroadcastGateway gateway = mock(WebSocketBroadcastGateway.class);
        final RunBroadcastCacheEvictionListener evictionListener;
        final PositionBroadcastListener positionListener;

        Instance() {
            factory.afterPropertiesSet();
            container.setConnectionFactory(factory);
            container.afterPropertiesSet();
            container.start();
            relay = new CacheEvictionRelay(new StringRedisTemplate(factory), container, new SimpleMeterRegistry(), channelSuffix);
            RunBroadcastCache cache = new RunBroadcastCache(Clock.systemUTC());
            evictionListener = new RunBroadcastCacheEvictionListener(cache, relay);
            evictionListener.listenForOtherInstances();
            positionListener = new PositionBroadcastListener(directory, cache, gateway);
        }

        /** 위치 1건을 방송하고 그 방송의 목적지 전부를 돌려준다. */
        List<String> broadcastOnce() {
            org.mockito.Mockito.clearInvocations(gateway);
            positionListener.broadcast(new RunPositionReceivedEvent(RUN_ID, new BigDecimal("37.5"), new BigDecimal("127.0"),
                    NOW, NOW, 1L, "정문", null));
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<BroadcastDelivery>> captor = ArgumentCaptor.forClass(List.class);
            verify(gateway).broadcast(org.mockito.ArgumentMatchers.eq("position"), org.mockito.ArgumentMatchers.eq(RUN_ID),
                    org.mockito.ArgumentMatchers.any(), captor.capture());
            return captor.getValue().stream().flatMap(delivery -> delivery.destinations().stream()).toList();
        }

        void close() throws Exception {
            container.destroy();
            factory.destroy();
        }
    }
}
