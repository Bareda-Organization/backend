package src.backend.global.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

import lombok.RequiredArgsConstructor;

import src.backend.global.error.StompErrorFrameHandler;
import src.backend.global.security.StompAuthChannelInterceptor;
import src.backend.global.security.StompSessionExpiry;
import src.backend.global.websocket.ConnectDeadlineFactory;
import src.backend.global.websocket.OpenWebSocketSessions;

/**
 * 실시간 위치·알림 채널(STOMP over WebSocket) 설정.
 * heartbeat(ping/pong) 간격은 사양이 정한 정책 값이 아니라 연결 유지용 운영값이라 {@link #HEARTBEAT_MS} 로 코드에 고정한다.
 * 이 브로커의 구독 채널·메시지 규격은 위치 · 실시간 전달 · 근접 알림 기능에 맞춰 설계됐다.
 * 실행기와 하트비트 스케줄러는 {@link WebSocketExecutorConfig} 가 만든다. 지금 구성은 단순 브로커({@code /topic} 하나 — 서버가 개별 전달하는 곳이 없어 {@code /queue}·{@code /user} 접두사를 두지 않는다) · 인바운드 인증 인터셉터 · 큐 상한이 있는 팬아웃 실행기 ·
 * 세션 송신 한도와 종료 코드 데코레이터다.
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /** STOMP heartbeat(ping/pong) 간격(ms) — 사양이 정한 정책 값이 아니라 연결 유지용 운영값이라 코드에 고정한다(IMPLEMENTATION_PLAN §7 규칙 10). */
    private static final long HEARTBEAT_MS = 10_000;

    private final StompAuthChannelInterceptor authChannelInterceptor;
    private final ForbiddenSubscriptionCloseFactory forbiddenSubscriptionCloseFactory;
    private final ReadIdleTimeoutFactory readIdleTimeoutFactory;
    private final StompSessionExpiry sessionExpiry;
    private final OpenWebSocketSessions openWebSocketSessions;
    private final ConnectDeadlineFactory connectDeadlineFactory;
    /** 실행기와 스케줄러는 채널 설정 메서드가 불릴 때 꺼낸다 — 설정 클래스를 만드는 시점에 실행기 빈을 먼저 만들지 않아 빈 생성 순서가 실행기를 이 클래스 안에 두던 때와 같다. */
    @Qualifier("inboundTaskExecutor")
    private final ObjectProvider<ThreadPoolTaskExecutor> inboundTaskExecutor;
    @Qualifier("outboundTaskExecutor")
    private final ObjectProvider<ThreadPoolTaskExecutor> outboundTaskExecutor;
    @Qualifier("heartbeatScheduler")
    private final ObjectProvider<TaskScheduler> heartbeatScheduler;
    @Value("${app.ws.allowed-origin-patterns}")
    private final String[] allowedOriginPatterns;
    /**
     * 세션 하나가 보낼 메시지를 버퍼링할 수 있는 최대 바이트 수 — 미설정 시
     * Spring 기본값 512KB. {@code WebSocketEnvelope} 페이로드는 실측 200~400바이트라 64KB 면 200배
     * 여유이고, 최대 세션(8,112, 09-09 부하 한계 측정 §7) 기준 512KB 는 약 4.1GB, 64KB 는 약 520MB다.
     */
    @Value("${app.ws.outbound.send-buffer-size-limit:65536}")
    private final int outboundSendBufferSizeLimit;
    /** 느린 구독자에게 메시지를 보내는 데 허용하는 최대 시간(ms) — Spring 기본값과 같은 10초를 명시로 고정한다. */
    @Value("${app.ws.outbound.send-time-limit:10000}")
    private final int outboundSendTimeLimit;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 로컬은 "*", 배포는 WS_ALLOWED_ORIGIN_PATTERNS 로 주입한 웹 출처만 허용한다 — EC2 운영은
        // docker-compose.prod.yml(DEPLOYMENT.md), 스테이징은 Cloudflare Tunnel 의 공개 주소 PUBLIC_URL(STAGING.md).
        // 네이티브 앱은 Origin 헤더를 보내지 않아 이 제한에 걸리지 않는다.
        registry.addEndpoint("/ws/location").setAllowedOriginPatterns(allowedOriginPatterns);
        // 거부 사유를 REST 와 같은 어휘(ErrorCode 이름)로 ERROR 프레임에 싣는다 — 기본 변환기는 채널
        // 인터셉터의 예외를 감싼 바깥 예외 문구만 실어 클라이언트가 원인을 특정하지 못한다.
        registry.setErrorHandler(new StompErrorFrameHandler());
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        // 같은 세션에 같은 순서로 발행한 메시지가 뒤집혀 도착하지 않게 한다(Ruling 899 C) — 클라이언트는 화면 값보다 오래된 시각의 메시지를 버린다.
        registry.setPreservePublishOrder(true);
        registry.enableSimpleBroker("/topic")
                .setHeartbeatValue(new long[]{HEARTBEAT_MS, HEARTBEAT_MS})
                .setTaskScheduler(heartbeatScheduler.getObject());
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authChannelInterceptor);
        registration.taskExecutor(inboundTaskExecutor.getObject());
    }

    /** 구독자에게 나가는 프레임의 인터셉터(세션 만료)와 쓰기 실행기({@code outboundTaskExecutor})를 건다. */
    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.interceptors(sessionExpiry);
        registration.taskExecutor(outboundTaskExecutor.getObject());
    }

    /**
     * SUBSCRIBE 거부 시 종료 코드를 4403 으로 바꾸는 세션 데코레이터를 등록한다 —
     * {@link ForbiddenSubscriptionCloseFactory} 참고. 세션 송신 한도(버퍼·시간)도 여기서 명시한다
     * — 미설정 시 Spring 기본값(512KB·10초)을 조용히 상속하기 때문이다.
     */
    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.addDecoratorFactory(forbiddenSubscriptionCloseFactory);
        // 나중에 등록한 것이 바깥이다 — 유휴 제한이 원본(Tomcat) 세션을 먼저 받아야 하므로 반드시 위 데코레이터보다 뒤에 둔다.
        registration.addDecoratorFactory(readIdleTimeoutFactory);
        // 열린 세션 보관소는 가장 바깥에 둔다 — 원본 세션을 받아야 계정 단위 종료(4500)가 그 세션을 직접 닫는다.
        registration.addDecoratorFactory(openWebSocketSessions);
        // CONNECT 마감 — 인증 전 소켓을 10초 뒤 닫는다. 원본 세션에 대고 close 하므로 위 데코레이터들과 순서가 상관없지만 한곳에 모은다.
        registration.addDecoratorFactory(connectDeadlineFactory);
        registration.setSendBufferSizeLimit(outboundSendBufferSizeLimit);
        registration.setSendTimeLimit(outboundSendTimeLimit);
    }

}
