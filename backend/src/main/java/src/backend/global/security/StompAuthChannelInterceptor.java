package src.backend.global.security;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;

/**
 * STOMP CONNECT 프레임에서 한 번만 인증한다(REST 의 매 요청 검증과 다르게, 세션 수립 시 1회).
 * 이후 같은 세션의 모든 메시지는 CONNECT 때 심어둔 Principal(AuthUser)을 그대로 사용한다.
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtTokenProvider tokenProvider;

    public StompAuthChannelInterceptor(JwtTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        // 주의: StompHeaderAccessor.wrap(message)로 얻은 접근자는 새 복사본이라 mutate 해도
        // 원본 message 에 반영되지 않는다. 인바운드 STOMP 채널의 메시지는 mutable 접근자와 함께
        // 만들어지므로, getAccessor 로 "그 접근자"를 그대로 받아와야 setUser 가 세션에 실제로 남는다.
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            String header = accessor.getFirstNativeHeader(HEADER);
            String token = (header != null && header.startsWith(PREFIX)) ? header.substring(PREFIX.length()) : null;
            if (token == null) {
                throw new IllegalArgumentException("WebSocket 연결에는 Authorization 헤더가 필요합니다");
            }
            try {
                Claims claims = tokenProvider.parse(token);
                if (!tokenProvider.isAccessToken(claims)) {
                    throw new IllegalArgumentException("access 토큰이 아닙니다");
                }
                accessor.setUser(tokenProvider.resolveAuthUser(claims));
            } catch (JwtException e) {
                throw new IllegalArgumentException("유효하지 않은 토큰입니다", e);
            }
        }
        return message;
    }
}
