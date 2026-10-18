package src.backend.global.redis;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.context.SmartLifecycle;

import lombok.extern.slf4j.Slf4j;

/**
 * Redis 에 못 붙어 서지 못한 구독({@link FailSoftRedisMessageListenerContainer#isSubscriptionPending()})을 정해진 간격(기본 2초, {@code app.relay.subscription-retry-ms})마다 다시 건다. 별도 스레드라 애플리케이션 기동과
 * 요청 처리를 막지 않는다. 구독이 선 뒤에는 스레드가 놀기만 한다(컨테이너의 재접속이 끊김을 맡는다).
 */
@Slf4j
public class RedisSubscriptionRetrier implements SmartLifecycle {

    private final FailSoftRedisMessageListenerContainer container;

    private final Duration retryDelay;

    private final AtomicLong attempts = new AtomicLong();

    private volatile Thread worker;

    private volatile boolean stopping;

    public RedisSubscriptionRetrier(FailSoftRedisMessageListenerContainer container, Duration retryDelay) {
        this.container = container;
        this.retryDelay = retryDelay;
    }

    /** 지금까지 구독을 다시 건 횟수. */
    public long retryAttempts() {
        return attempts.get();
    }

    @Override
    public void start() {
        stopping = false;
        Thread thread = new Thread(this::retryWhilePending, "redis-subscription-retrier");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
    }

    private void retryWhilePending() {
        boolean wasPending = false;
        while (!stopping) {
            try {
                Thread.sleep(retryDelay);
                if (container.isSubscriptionPending()) {
                    wasPending = true;
                    attempts.incrementAndGet();
                    container.retrySubscription();
                } else if (wasPending) {
                    wasPending = false;
                    log.info("Redis 구독이 다시 섰다");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.warn("Redis 구독 재시도 실패 — 다음 주기에 다시 시도한다", e);
            }
        }
    }

    @Override
    public void stop() {
        stopping = true;
        Thread thread = worker;
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        Thread thread = worker;
        return thread != null && thread.isAlive();
    }
}
