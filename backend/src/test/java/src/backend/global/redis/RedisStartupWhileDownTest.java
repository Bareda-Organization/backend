package src.backend.global.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import testsupport.redis.Outage;
import testsupport.redis.OutageRedis;
import testsupport.redis.OutageRedisContext;

/**
 * Redis 가 멈춘 채로 백엔드가 기동해도 기동에 성공하고(컨테이너 헬스체크의 start_period 안에 건강해진다 — Ruling 932), Redis 가 돌아오면 구독이 스스로 서서 방송이 다시 도착한다.
 * 구독 컨테이너가 기동 때 Redis 에 못 붙는다고 애플리케이션 기동을 실패시키면 재시작이 끝없이 반복돼 대체 경로로 서비스를 이을 서버 자체가 뜨지 못한다.
 */
class RedisStartupWhileDownTest {

    private static final Duration SUBSCRIPTION_LIMIT = Duration.ofSeconds(12);

    private static OutageRedis redis;

    @BeforeAll
    static void startRedis() {
        redis = OutageRedis.start();
    }

    @AfterAll
    static void stopRedis() {
        redis.close();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Outage.class)
    @DisplayName("Redis 가 멈추거나 답하지 않는 채 기동해도 컨텍스트가 뜨고, Redis 가 돌아오면 12초 안에 구독이 서서 메시지를 받는다")
    void 장애_중_기동해도_뜨고_돌아오면_구독이_선다(Outage outage) {
        outage.begin(redis);
        try {
            OutageRedisContext.runner(redis).run(context -> {
                assertThat(context).as("Redis 가 안 되는 동안의 기동").hasNotFailed();
                CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
                RedisMessageListenerContainer container = context.getBean(RedisMessageListenerContainer.class);
                container.addMessageListener((message, pattern) -> received.add(new String(message.getBody(), StandardCharsets.UTF_8)),
                        new ChannelTopic("startup-probe"));

                outage.end(redis);
                StringRedisTemplate template = context.getBean(StringRedisTemplate.class);
                long deadline = System.nanoTime() + SUBSCRIPTION_LIMIT.toNanos();
                while (received.isEmpty() && System.nanoTime() < deadline) {
                    OutageRedisContext.timeOf(() -> template.convertAndSend("startup-probe", "ping"));
                    Thread.sleep(300);
                }

                assertThat(received).as("Redis 가 돌아온 뒤 %s 안에 구독으로 받은 메시지".formatted(SUBSCRIPTION_LIMIT)).isNotEmpty();
            });
        } finally {
            restoreRedis(outage);
        }
    }

    /** 시험 안에서 이미 되돌렸으면 아무 일도 없다 — 컨텍스트가 뜨지 못한 채 끝난 경우에도 다음 시험이 멈춘 Redis 를 물려받지 않게 한다. */
    private static void restoreRedis(Outage outage) {
        try {
            outage.end(redis);
        } catch (RuntimeException alreadyRestored) {
            // 이미 되돌려져 있다
        }
    }
}
