package src.backend.global.config;

import org.springframework.beans.factory.annotation.Value;
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

    /**
     * 기본 스레드 수 8 — 발송 1건은 기기마다 FCM HTTP 를 순차 호출해 대부분 응답 대기라 스레드가 싸고, 건당 DB 연결은
     * 짧은 SQL 4개뿐이다(R46 D #15). 2개일 때 처리율은 FCM 지연 150ms 기준 약 13건/s 로 10대 동시 출발(약 260건)에
     * 20초가 걸렸다. 8개면 약 4배다. 연결 풀 20 중 스케줄러 최대 10 · 확정 워커 3 과 겹쳐도 풀이 차면
     * {@code connection-timeout} 3초 뒤 실패해 그 행이 {@code pending} 으로 남고 워커가 다시 집는다.
     */
    private static final int DEFAULT_THREADS = 8;

    /** 대기열 상한 — 무한 대기열은 발송 채널이 멈췄을 때 힙을 채운다. 넘치면 워커 재시도로 넘어간다. */
    private static final int QUEUE_CAPACITY = 1000;

    /**
     * {@code defaultCandidate = false} — 이름으로 지정한 곳({@code NotificationDispatchListener})에만 주입된다.
     * 일반 후보로 두면 Spring Boot 가 이 빈을 기본 실행기로 잡아 STOMP 송신 채널 실행기까지 이 2스레드로
     * 바뀐다(병합 검증에서 {@code WebSocketOutboundPoolSizeTest} 가 7 대신 2 를 보고 드러남).
     */
    @Bean(defaultCandidate = false)
    public ThreadPoolTaskExecutor notificationDispatchExecutor(
            @Value("${app.notification.dispatch.core-pool-size:" + DEFAULT_THREADS + "}") int threads) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix("notification-dispatch-");
        return executor;
    }
}
