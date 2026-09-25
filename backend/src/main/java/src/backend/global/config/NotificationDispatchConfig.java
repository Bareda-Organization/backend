package src.backend.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 커밋 직후 즉시 발송(TECH_DECISIONS §7.2 ①)을 요청 스레드에서 떼어 돌리는 전용 실행기(BR-069).
 *
 * <p>요청 스레드에서 돌면 응답이 수신자 수만큼 늦고, 원 트랜잭션의 연결을 쥔 채 발송 전이 트랜잭션이 두 번째
 * 연결을 요구해 동시 요청이 풀 크기를 넘으면 서로의 반납을 기다린다. 대기열이 차면 제출이 거절되고 그 행은
 * {@code pending} 으로 남아 아웃박스 워커가 다시 집는다 — 유실은 없다.
 */
@Configuration
public class NotificationDispatchConfig {

    /** ponytail: 고정 2개 — 실 푸시 채널(FCM) 응답 시간을 잰 뒤 조정한다. 발송 1건이 연결을 1개만 쥔다. */
    private static final int THREADS = 2;

    /** 대기열 상한 — 무한 대기열은 발송 채널이 멈췄을 때 힙을 채운다. 넘치면 워커 재시도로 넘어간다. */
    private static final int QUEUE_CAPACITY = 1000;

    @Bean
    public ThreadPoolTaskExecutor notificationDispatchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(THREADS);
        executor.setMaxPoolSize(THREADS);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix("notification-dispatch-");
        return executor;
    }
}
