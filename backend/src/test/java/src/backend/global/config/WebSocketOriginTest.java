package src.backend.global.config;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;

import src.backend.global.security.StompAuthChannelInterceptor;

/**
 * STOMP 허용 출처가 코드에 박히지 않고 설정에서 오는지 고정한다.
 * 웹이 Vercel 로 분리돼 크로스오리진이 되므로, 이 값이 배포 환경마다 달라져야 한다.
 */
class WebSocketOriginTest {

    @Test
    @DisplayName("허용 출처를 설정값 그대로 엔드포인트에 넘긴다")
    void passesConfiguredOriginsToEndpoint() {
        StompAuthChannelInterceptor interceptor = mock(StompAuthChannelInterceptor.class);
        String[] origins = {"https://app.example.com", "https://preview.example.com"};

        WebSocketConfig config = new WebSocketConfig(interceptor, 10_000L, origins);

        StompEndpointRegistry registry = mock(StompEndpointRegistry.class);
        StompWebSocketEndpointRegistration registration = mock(StompWebSocketEndpointRegistration.class);
        when(registry.addEndpoint(eq("/ws/location"))).thenReturn(registration);

        config.registerStompEndpoints(registry);

        verify(registration).setAllowedOriginPatterns(
                "https://app.example.com", "https://preview.example.com");
    }
}
