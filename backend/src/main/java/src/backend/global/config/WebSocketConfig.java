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

import src.backend.global.security.StompAuthChannelInterceptor;

/**
 * 실시간 위치·알림 채널(STOMP over WebSocket) 설정 — 학생 좌표 수신(인바운드)과
 * 관련자에게로의 push(아웃바운드) 양방향을 이 브로커로 처리한다(Phase 3).
 * heartbeat(기본 10초 간격 ping/pong)를 켜서, REST 폴링보다 훨씬 빠르게 연결 끊김을 감지한다 —
 * 이 heartbeat 타임아웃이 {@code LocationSocketEventListener}/{@code ConnectionLossScheduler}가
 * "학생 연결이 끊겼다"고 판단하는 근거가 된다.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor authChannelInterceptor;
    private final long heartbeatMs;

    public WebSocketConfig(StompAuthChannelInterceptor authChannelInterceptor,
                           @Value("${app.connection.heartbeat-ms:10000}") long heartbeatMs) {
        this.authChannelInterceptor = authChannelInterceptor;
        this.heartbeatMs = heartbeatMs;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 네이티브 모바일 클라이언트 전용 채널이라 브라우저 CORS origin 제한을 걸지 않는다(MVP).
        registry.addEndpoint("/ws/location").setAllowedOriginPatterns("*");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        // Phase 3: /user 프리픽스로 보낸 메시지는 세션별 목적지(/queue/**-user<sessionId>)로
        // 재작성되어 심플 브로커를 거쳐 배달된다 — 그래서 /queue 도 브로커 프리픽스로 열어둬야 한다.
        registry.setUserDestinationPrefix("/user");
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[]{heartbeatMs, heartbeatMs})
                .setTaskScheduler(heartbeatScheduler());
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authChannelInterceptor);
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
