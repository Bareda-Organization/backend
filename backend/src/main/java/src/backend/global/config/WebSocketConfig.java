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
    @Value("${app.ws.allowed-origin-patterns}")
    private final String[] allowedOriginPatterns;

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
