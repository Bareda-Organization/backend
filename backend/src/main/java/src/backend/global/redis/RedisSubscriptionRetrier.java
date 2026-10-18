package src.backend.global.redis;

import java.time.Duration;

import org.springframework.context.SmartLifecycle;

import lombok.extern.slf4j.Slf4j;

/**
 * Redis 에 못 붙어 서지 못한 구독({@link FailSoftRedisMessageListenerContainer#isSubscriptionPending()})을 {@value #RETRY_SECONDS}초마다 다시 건다. 별도 스레드라 애플리케이션 기동과
 * 요청 처리를 막지 않는다. 구독이 선 뒤에는 스레드가 놀기만 한다(컨테이너의 재접속이 끊김을 맡는다).
 */
@Slf4j
public class RedisSubscriptionRetrier implements SmartLifecycle {

    static final int RETRY_SECONDS = 2;

    private static final Duration RETRY_DELAY = Duration.ofSeconds(RETRY_SECONDS);

    private final FailSoftRedisMessageListenerContainer container;

    private volatile Thread worker;

    private volatile boolean stopping;

    public RedisSubscriptionRetrier(FailSoftRedisMessageListenerContainer container) {
        this.container = container;
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
                Thread.sleep(RETRY_DELAY);
                if (container.isSubscriptionPending()) {
                    wasPending = true;
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
