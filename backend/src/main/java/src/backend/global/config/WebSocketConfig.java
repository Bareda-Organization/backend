package src.backend.global.config;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

import lombok.RequiredArgsConstructor;

import src.backend.global.error.StompErrorFrameHandler;
import src.backend.global.security.StompAuthChannelInterceptor;
import src.backend.global.security.StompSessionExpiry;

/**
 * 실시간 위치·알림 채널(STOMP over WebSocket) 설정.
 * heartbeat(ping/pong) 간격은 사양이 정한 정책 값이 아니라 연결 유지용 운영값이라 {@link #HEARTBEAT_MS} 로 코드에 고정한다
 * — 옛 도메인({@code LocationSocketEventListener}·{@code ConnectionLossScheduler})은 바래다 재구축(Phase 0)에서
 * 제거됐고, 이 브로커의 재사용(구독 채널·메시지 규격)은 Phase 10(위치 · 실시간 전달 · 근접 알림)에서 새로 설계한다.
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /** STOMP heartbeat(ping/pong) 간격(ms) — 사양이 정한 정책 값이 아니라 연결 유지용 운영값이라 코드에 고정한다(IMPLEMENTATION_PLAN §7 규칙 10). */
    private static final long HEARTBEAT_MS = 10_000;

    private final StompAuthChannelInterceptor authChannelInterceptor;
    private final ForbiddenSubscriptionCloseFactory forbiddenSubscriptionCloseFactory;
    private final StompSessionExpiry sessionExpiry;
    @Value("${app.ws.allowed-origin-patterns}")
    private final String[] allowedOriginPatterns;
    @Value("${app.ws.outbound.core-pool-size:0}")
    private final int outboundCorePoolSize;
    /** 팬아웃 실행기 큐 상한(BR-170) — 무한 큐는 느린 구독자가 있을 때 힙을 채운다. */
    @Value("${app.ws.outbound.queue-capacity:5000}")
    private final int outboundQueueCapacity;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 로컬은 "*", 배포는 웹(Vercel) 출처만 허용한다 — 웹이 API 와 다른 출처가 됐기 때문이다.
        // 네이티브 앱은 Origin 헤더를 보내지 않아 이 제한에 걸리지 않는다.
        registry.addEndpoint("/ws/location").setAllowedOriginPatterns(allowedOriginPatterns);
        // 거부 사유를 REST 와 같은 어휘(ErrorCode 이름)로 ERROR 프레임에 싣는다 — 기본 변환기는 채널
        // 인터셉터의 예외를 감싼 바깥 예외 문구만 실어 클라이언트가 원인을 특정하지 못한다.
        registry.setErrorHandler(new StompErrorFrameHandler());
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        // Phase 3: /user 프리픽스로 보낸 메시지는 세션별 목적지(/queue/**-user<sessionId>)로
        // 재작성되어 심플 브로커를 거쳐 배달된다 — 그래서 /queue 도 브로커 프리픽스로 열어둬야 한다.
        registry.setUserDestinationPrefix("/user");
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[]{HEARTBEAT_MS, HEARTBEAT_MS})
                .setTaskScheduler(heartbeatScheduler());
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authChannelInterceptor);
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
     * <p>큐는 {@link #outboundQueueCapacity} 로 상한을 둔다(BR-170) — 전에는 무한이라 넘치면
     * 거부가 아니라 <b>지연과 힙 증가</b>로 나타났고, 힙이 차면 GC 압력이 DB 커넥션 보유 시간을
     * 늘려 <b>위치 수신 HTTP 까지 함께 무너졌다</b>(2026-09-09 부하 한계 측정 — 큐 520만 건 ·
     * 힙 3.8GB · 커넥션 획득 30초 타임아웃). 큐가 가득 차도 거부 정책은 {@code CallerRunsPolicy} —
     * 호출 스레드가 대신 보내 메시지를 잃지 않는다. 위치({@code position}) 이벤트만 큐 포화를
     * 미리 보고 스스로 버린다({@link src.backend.global.websocket.WebSocketBroadcastGateway},
     * Ruling 349) — 이 실행기 자체는 이벤트 종류를 모른다.
     */
    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.interceptors(sessionExpiry);
        registration.taskExecutor(outboundTaskExecutor());
    }

    /**
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

    /**
     * SUBSCRIBE 거부 시 종료 코드를 4403 으로 바꾸는 세션 데코레이터를 등록한다(목표 7) —
     * {@link ForbiddenSubscriptionCloseFactory} 참고.
     */
    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.addDecoratorFactory(forbiddenSubscriptionCloseFactory);
    }

    @Bean
    public TaskScheduler heartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.initialize();
        return scheduler;
    }
}
