package src.backend.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
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
     * 값이 초당 방송 건수의 천장을 정한다. 큐는 무한이라 넘치면 거부가 아니라 <b>지연과 힙 증가</b>로
     * 나타나고, 힙이 차면 GC 압력이 DB 커넥션 보유 시간을 늘려 <b>위치 수신 HTTP 까지 함께 무너진다</b>
     * (2026-09-09 부하 한계 측정 — 큐 520만 건 · 힙 3.8GB · 커넥션 획득 30초 타임아웃).
     *
     * <p>⚠ 값을 주지 않으면 Spring 기본값인 <b>코어 수 × 2</b> 가 쓰인다 — 기계가 바뀌면 팬아웃
     * 용량도 함께 바뀐다는 뜻이다. 10코어 노트북은 20, <b>2 vCPU 운영 인스턴스는 4</b> 다.
     * 기본값을 여기서 바꾸지 않고 속성으로 두는 이유는, 운영 인스턴스 크기에 맞는 값이 측정으로
     * 정해져야 하고 그 값이 정해지기 전까지는 현 동작을 그대로 두는 편이 안전하기 때문이다.
     */
    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.interceptors(sessionExpiry);
        if (outboundCorePoolSize <= 0) {
            // taskExecutor() 를 부르는 것 자체가 기본 실행기를 교체하므로, 값이 없으면 손대지 않는다.
            return;
        }
        registration.taskExecutor()
                .corePoolSize(outboundCorePoolSize)
                .maxPoolSize(outboundCorePoolSize);
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
