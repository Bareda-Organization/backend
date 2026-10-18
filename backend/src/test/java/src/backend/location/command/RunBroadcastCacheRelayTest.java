package src.backend.location.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * 학생이 결석 처리되면 그 인스턴스의 위치 방송은 다음 건부터 그 학생 채널로 나가지 않는다(Ruling 900 D). 위치 방송은 회차의 학생 목록을 캐시에 들고 있어, 결석 처리 뒤 목록이 비워지지
 * 않으면 결석한 학생의 학부모 지도에 위치가 계속 나간다. 인스턴스 = 캐시 + 비움 리스너 + 위치 방송 리스너 + 비움 중계(실제 Redis 연결).
 */
class RunBroadcastCacheRelayTest extends RedisTestContainerBase {

    private static final long RUN_ID = 10L;
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-18T09:00:00+09:00");

    private final String channelSuffix = ":" + UUID.randomUUID();
    private Instance instance;

    @BeforeEach
    void openInstance() {
        instance = new Instance();
    }

    @AfterEach
    void close() throws Exception {
        instance.close();
    }

    @Test
    @DisplayName("결석 처리 신호를 내면 다음 위치 방송은 그 학생 채널로 0건이다")
    void 결석_처리_뒤_위치_방송은_그_학생_채널로_나가지_않는다() throws Exception {
        given(instance.directory.studentIdsExcludingAbsent(RUN_ID)).willReturn(List.of(1L, 2L));
        assertThat(instance.broadcastOnce()).as("결석 처리 전 학생 채널").contains(WebSocketDestinations.studentRun(1L));

        // 학생 1 이 결석 처리돼 DB 가 바뀐 뒤: 커밋 뒤 리스너가 신호를 낸다
        given(instance.directory.studentIdsExcludingAbsent(RUN_ID)).willReturn(List.of(2L));
        instance.evictionListener.onRidersChanged(new RunRidersChangedEvent(RUN_ID));

        assertThat(instance.broadcastOnce()).as("결석 처리 뒤 학생 채널")
                .doesNotContain(WebSocketDestinations.studentRun(1L)).contains(WebSocketDestinations.studentRun(2L));
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
