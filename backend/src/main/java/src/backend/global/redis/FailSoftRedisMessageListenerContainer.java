package src.backend.global.redis;

import java.util.Collection;

import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.Topic;
import org.springframework.data.redis.connection.MessageListener;

import lombok.extern.slf4j.Slf4j;

/**
 * Redis 에 붙지 못해도 예외를 던지지 않는 구독 컨테이너 — 기본 컨테이너는 시작 · 구독 등록 때 첫 연결이 실패하면 예외를 던져 애플리케이션 기동이 실패하고, Redis 가 멈춘 채 다시 시작한
 * 백엔드가 끝없이 재시작만 반복한다(대체 경로로 서비스를 이을 서버가 뜨지 못한다, Ruling 932). 실패는 {@link #isSubscriptionPending()} 으로 남기고
 * {@link RedisSubscriptionRetrier} 가 {@link #retrySubscription()} 으로 붙을 때까지 다시 시도한다. 구독 등록은 예외가 나도 컨테이너에 이미 기록돼 있어 다시 붙으면 함께 선다.
 * 붙은 뒤의 끊김은 컨테이너 자신의 재접속이 맡는다.
 */
@Slf4j
public class FailSoftRedisMessageListenerContainer extends RedisMessageListenerContainer {

    private volatile boolean subscriptionPending;

    @Override
    public void start() {
        try {
            super.start();
            subscriptionPending = false;
        } catch (RuntimeException e) {
            markPending("시작", e);
        }
    }

    @Override
    public void addMessageListener(MessageListener listener, Collection<? extends Topic> topics) {
        try {
            super.addMessageListener(listener, topics);
        } catch (RuntimeException e) {
            markPending("구독 등록", e);
        }
    }

    /** 구독이 Redis 에 서 있다 — 연결이 끊겨 다시 거는 중이거나 시작하지 못했으면 거짓이다. */
    public boolean isSubscribed() {
        return !subscriptionPending && isListening();
    }

    /** 구독이 아직 Redis 에 서지 못했다. */
    public boolean isSubscriptionPending() {
        return subscriptionPending;
    }

    /** 컨테이너를 멈췄다 다시 시작해 등록된 구독 전체를 다시 건다 — 이미 기록된 구독 목록은 그대로다. */
    public void retrySubscription() {
        stop();
        start();
    }

    private void markPending(String what, RuntimeException e) {
        if (!subscriptionPending) {
            log.warn("Redis 구독 컨테이너 {} 실패 — Redis 가 돌아올 때까지 다시 시도한다. 그동안 방송 중계 · 캐시 비움 신호는 대체 경로로 돈다", what, e);
        }
        subscriptionPending = true;
    }
}
