package src.backend.global.config;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;

import src.backend.global.security.StompAuthChannelInterceptor;
import src.backend.global.security.StompSessionExpiry;
import src.backend.global.websocket.ConnectDeadlineFactory;
import src.backend.global.websocket.OpenWebSocketSessions;

/**
 * STOMP 허용 출처가 코드에 박히지 않고 설정에서 오는지 고정한다.
 * 웹이 Vercel 로 분리돼 크로스오리진이 되므로, 이 값이 배포 환경마다 달라져야 한다.
 */
class WebSocketOriginTest {

    @Test
    @DisplayName("허용 출처를 설정값 그대로 엔드포인트에 넘긴다")
    void passesConfiguredOriginsToEndpoint() {
        StompAuthChannelInterceptor interceptor = mock(StompAuthChannelInterceptor.class);
        ForbiddenSubscriptionCloseFactory closeFactory = mock(ForbiddenSubscriptionCloseFactory.class);
        String[] origins = {"https://app.example.com", "https://preview.example.com"};

        // 실행기 셋과 뒤 두 인자(세션 송신 버퍼·시간 한도)는 이 시험이 허용 출처만 보므로 아무 값이나 준다(실행기 크기는
        // WebSocketOutboundPoolSizeTest·WebSocketOutboundQueueCapacityTest, 송신 한도는 WebSocketOutboundSendLimitTest 가 맡는다).
        WebSocketConfig config = new WebSocketConfig(interceptor, closeFactory, mock(ReadIdleTimeoutFactory.class),
                mock(StompSessionExpiry.class), mock(OpenWebSocketSessions.class), mock(ConnectDeadlineFactory.class),
                mock(ObjectProvider.class), mock(ObjectProvider.class), mock(ObjectProvider.class), origins, 0, 0);

        StompEndpointRegistry registry = mock(StompEndpointRegistry.class);
        StompWebSocketEndpointRegistration registration = mock(StompWebSocketEndpointRegistration.class);
        given(registry.addEndpoint(eq("/ws/location"))).willReturn(registration);

        config.registerStompEndpoints(registry);

        verify(registration).setAllowedOriginPatterns(
                "https://app.example.com", "https://preview.example.com");
    }
}
