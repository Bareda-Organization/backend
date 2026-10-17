package src.backend.global.cache;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongConsumer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;

import lombok.extern.slf4j.Slf4j;

/**
 * 인스턴스마다 따로 들고 있는 캐시의 항목 비움을 다른 인스턴스에 알린다(Ruling 900 D) — 한 인스턴스에서 계정이 차단되거나 결석 처리되면 나머지 인스턴스의 해당 항목도 바로 비워진다.
 * 신호를 놓쳐도 각 캐시의 만료가 낡은 값의 상한이라 이 중계는 <b>지연을 줄이는 장치</b>이지 정합성의 유일한 근거가 아니다.
 *
 * <p>비움은 항목 하나(캐시 이름 + 숫자 키)를 가리키는 짧은 문자열 {@code 발행자|캐시|키} 한 줄이다. 발행한 인스턴스는 자기 캐시를 호출 자리에서 직접 비우므로, 자기가 낸 신호가
 * Redis 를 돌아와도 처리하지 않는다. {@link #publish} 는 예외를 던지지 않는다 — Redis 가 죽었다고 상태 변경 트랜잭션의 커밋 뒤 처리가 실패하면 안 된다(실패는 지표로 센다).
 */
@Component
@Slf4j
public class CacheEvictionRelay {

    /** 계정 상태 캐시({@code AccountStanding}) — 키는 계정 id. */
    public static final String ACCOUNT_STANDING = "account-standing";

    /** 위치 방송 캐시({@code RunBroadcastCache})의 정차 · 학생 목록 둘 다 — 키는 회차 id. */
    public static final String RUN_ALL = "run-all";

    /** 위치 방송 캐시의 정차 목록만 — 키는 회차 id. */
    public static final String RUN_STOPS = "run-stops";

    /** 위치 방송 캐시의 학생 목록만 — 키는 회차 id. */
    public static final String RUN_STUDENTS = "run-students";

    private static final String CHANNEL = "schoolbus:relay:cache-evict";

    private static final String SEPARATOR = "|";

    private final String origin = UUID.randomUUID().toString();
    private final Map<String, LongConsumer> handlers = new ConcurrentHashMap<>();
    private final StringRedisTemplate redis;
    private final MeterRegistry meterRegistry;
    private final String channel;

    public CacheEvictionRelay(StringRedisTemplate redis, RedisMessageListenerContainer container,
            MeterRegistry meterRegistry, @Value("${app.relay.channel-suffix:}") String channelSuffix) {
        this.redis = redis;
        this.meterRegistry = meterRegistry;
        this.channel = CHANNEL + channelSuffix;
        container.addMessageListener(this::onMessage, new ChannelTopic(channel));
    }

    /** 다른 인스턴스가 이 캐시의 항목을 비웠다는 신호를 받으면 {@code evict} 로 이 인스턴스의 항목을 비운다. */
    public void register(String cache, LongConsumer evict) {
        handlers.put(cache, evict);
    }

    /** 이 캐시의 항목 하나를 비웠다고 모든 인스턴스에 알린다 — 실패는 기록하고 센다. */
    public void publish(String cache, long key) {
        try {
            redis.convertAndSend(channel, origin + SEPARATOR + cache + SEPARATOR + key);
        } catch (RuntimeException e) {
            meterRegistry.counter("schoolbus.cache.evict.relay.failed", "cache", cache).increment();
            log.warn("캐시 비움 신호 발행 실패 — 다른 인스턴스는 만료까지 옛 값을 쓴다. cache={} key={}", cache, key, e);
        }
    }

    private void onMessage(Message message, byte[] pattern) {
        try {
            String[] parts = new String(message.getBody(), StandardCharsets.UTF_8).split("\\|", 3);
            LongConsumer handler = handlers.get(parts[1]);
            if (!origin.equals(parts[0]) && handler != null) {
                handler.accept(Long.parseLong(parts[2]));
            }
        } catch (RuntimeException e) {
            log.warn("캐시 비움 신호 처리 실패", e);
        }
    }
}
