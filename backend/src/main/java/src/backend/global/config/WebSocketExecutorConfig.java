package src.backend.global.config;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.broker.DefaultSubscriptionRegistry;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import src.backend.global.websocket.BroadcastTask;
import src.backend.global.websocket.ConnectDeadlineFactory;

/**
 * 실시간 채널(STOMP over WebSocket)이 쓰는 실행기와 스케줄러 — 수신 채널 · 팬아웃(송신) · 커밋 뒤 방송 리스너 실행기와 하트비트 스케줄러, 브로커 구독 조회 보관 상한.
 * {@link WebSocketConfig} 가 채널 설정에서 이 빈들을 이름으로 꽂는다.
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class WebSocketExecutorConfig {

    /** 하트비트 스케줄러 스레드 수 — 브로커 하트비트와 CONNECT 마감({@link ConnectDeadlineFactory})이 함께 쓴다. 1개면 하나가 막힐 때 다른 쪽이 멈춘다. */
    static final int HEARTBEAT_SCHEDULER_POOL_SIZE = 2;

    /** 심플 브로커 목적지별 구독 조회 보관 상한 — 기본 1024 는 학생 채널 수천 개를 담지 못해 조회가 계속 다시 계산된다. */
    static final int SUBSCRIPTION_CACHE_LIMIT = 8192;

    /** 수신 채널 실행기 스레드 수 — CONNECT · SUBSCRIBE · SEND 인가가 DB 를 읽으므로 코어 수에 묶지 않고 값으로 둔다. */
    @Value("${app.ws.inbound.core-pool-size:8}")
    private final int inboundCorePoolSize;
    /** 수신 채널 실행기 큐 상한 — 기본은 무한이라 DB 가 느려지면 프레임이 힙에 쌓인다. 넘치면 읽는 스레드가 직접 처리해 그 연결만 느려진다. */
    @Value("${app.ws.inbound.queue-capacity:1000}")
    private final int inboundQueueCapacity;
    @Value("${app.ws.outbound.core-pool-size:0}")
    private final int outboundCorePoolSize;
    /** 팬아웃 실행기 큐 상한(BR-170) — 무한 큐는 느린 구독자가 있을 때 힙을 채운다. */
    @Value("${app.ws.outbound.queue-capacity:5000}")
    private final int outboundQueueCapacity;

    /**
     * 수신 채널 실행기 — 스레드 수 · 큐 상한을 명시한다. 기본 실행기는 큐가 무한이다. {@code outboundTaskExecutor} 와 같은 이유로 일반 후보에서 뺀다.
     * 큐가 차면 거부하지 않고 읽는 스레드가 직접 처리한다 — 프레임을 잃지 않고 그 연결의 읽기만 느려진다.
     */
    @Bean(defaultCandidate = false)
    public ThreadPoolTaskExecutor inboundTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("clientInboundChannel-");
        executor.setCorePoolSize(inboundCorePoolSize);
        executor.setMaxPoolSize(inboundCorePoolSize);
        executor.setQueueCapacity(inboundQueueCapacity);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /** 심플 브로커의 구독 조회 보관 상한을 올린다. 브로커 빈이 만들어진 직후에 손댄다. */
    @Bean
    static BeanPostProcessor subscriptionCacheLimitPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof SimpleBrokerMessageHandler broker
                        && broker.getSubscriptionRegistry() instanceof DefaultSubscriptionRegistry registry) {
                    registry.setCacheLimit(SUBSCRIPTION_CACHE_LIMIT);
                }
                return bean;
            }
        };
    }

    /**
     * 커밋 뒤 방송 리스너가 방송 작업을 넘기는 실행기 — {@code AFTER_COMMIT} 콜백은 요청 트랜잭션이 활성이고 커넥션을 아직 쥔 채 돌아서(실측), 그
     * 안에서 조회 · 방송하면 방송 시간만큼 커넥션을 더 쥔다. 콜백은 이 실행기에 제출만 하고 돌아가 요청 스레드가 커넥션을 먼저 반납한다.
     *
     * <p>스레드는 1개다 — 승하차 · 운행 상태 방송은 사람 속도(분당 수십 건 이하)라 1개로 충분하고, 커밋 순서대로 방송돼야 클라이언트가 오래된 메시지를 버리는 기준
     * ({@code changed_at})과 방송 순서가 어긋나지 않는다. 큐가 차면 제출한 스레드가 직접 방송해 메시지를 잃지 않는다. 위치(position) 방송은 이 실행기를 쓰지 않는다.
     */
    @Bean(defaultCandidate = false)
    public ThreadPoolTaskExecutor broadcastListenerExecutor(
            @Value("${app.ws.broadcast.queue-capacity:1000}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("broadcast-listener-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(queueCapacity);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 리스너가 읽는 DB 조회 같은 방송 밖의 실패가 작업 스레드를 죽이지 않게 여기서 한 번 로그로 남긴다 — 목적지별 전송 실패는 게이트웨이가 센다
        executor.setTaskDecorator(task -> () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                if (task instanceof BroadcastTask broadcast) {
                    log.warn("{} 방송 작업 실패 — runId={}", broadcast.event(), broadcast.runId(), e);
                } else {
                    log.warn("방송 리스너 작업 실패", e);
                }
            }
        });
        executor.initialize();
        return executor;
    }

    /**
     * 구독자에게 프레임을 실제로 써 내보내는 실행기의 스레드 수 — 팬아웃 처리량의 상한이다.
     *
     * <p>구독자 N명 토픽에 1건을 보내면 이 실행기에 <b>N건의 쓰기 태스크</b>가 쌓인다. 그래서 이
     * 값이 초당 방송 건수의 천장을 정한다.
     *
     * <p>⚠ 값을 주지 않으면 <b>코어 수 × 2</b> 가 쓰인다 — 기계가 바뀌면 팬아웃 용량도 함께
     * 바뀐다는 뜻이다. 10코어 노트북은 20, <b>2 vCPU 운영 인스턴스는 4</b> 다. 기본값을 여기서
     * 바꾸지 않고 속성으로 두는 이유는, 운영 인스턴스 크기에 맞는 값이 측정으로 정해져야 하고
     * 그 값이 정해지기 전까지는 현 동작을 그대로 두는 편이 안전하기 때문이다.
     *
     * <p>큐는 {@link #outboundQueueCapacity} 로 상한을 둔다(BR-170). 상한이 없으면 넘치는 부하가
     * 거부가 아니라 <b>지연과 힙 증가</b>로 나타나고, 힙이 차면 GC 압력이 DB 커넥션 보유 시간을
     * 늘려 <b>위치 수신 HTTP 까지 함께 무너졌다</b>(부하 한계 측정 — 큐 520만 건 ·
     * 힙 3.8GB · 커넥션 획득 30초 타임아웃). 큐가 가득 차도 거부 정책은 {@code CallerRunsPolicy} —
     * 호출 스레드가 대신 보내 메시지를 잃지 않는다. 위치({@code position}) 이벤트만 큐 포화를
     * 미리 보고 스스로 버린다({@link src.backend.global.websocket.WebSocketBroadcastGateway},
     * Ruling 349) — 이 실행기 자체는 이벤트 종류를 모른다.
     *
     * <p>큐 길이는 Spring Boot 의 {@code TaskExecutorMetricsAutoConfiguration} 이 이 빈을 빈 이름
     * (메서드 이름 {@code outboundTaskExecutor})으로 자동 계측한다(실측 확인 —
     * {@code executor_queued_tasks{name="outboundTaskExecutor"}}가 코드 추가 없이 이미 노출됨).
     *
     * 팬아웃 실행기를 독립된 {@code @Bean} 으로 둔다 — Spring 프레임워크가 내부에서 만드는
     * {@code clientOutboundChannelExecutor} 빈은 선언 타입이 {@code Executor} 뿐이라, 다른 무언가가
     * 먼저 만들어 두지 않은 채(예: {@code SimpMessagingTemplate} 을 {@code @MockitoBean} 으로 대체한
     * 시험) {@link WebSocketBroadcastGateway} 가 {@code ThreadPoolTaskExecutor} 타입으로 그 빈을
     * 먼저 요구하면 타입 사전판별에서 걸러져 {@code NoSuchBeanDefinitionException} 이 난다. 이 빈은
     * 선언 타입 자체가 {@code ThreadPoolTaskExecutor} 라 그 문제가 없다.
     *
     * <p>{@code defaultCandidate = false} — {@code NotificationDispatchConfig.notificationDispatchExecutor}
     * 와 같은 이유(그 클래스 자바독)로 일반 후보에서 뺀다.
     */
    @Bean(defaultCandidate = false)
    public ThreadPoolTaskExecutor outboundTaskExecutor() {
        int poolSize = outboundCorePoolSize > 0 ? outboundCorePoolSize : Runtime.getRuntime().availableProcessors() * 2;
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("clientOutboundChannel-");
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(outboundQueueCapacity);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    @Bean
    public TaskScheduler heartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(HEARTBEAT_SCHEDULER_POOL_SIZE);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.initialize();
        return scheduler;
    }
}
