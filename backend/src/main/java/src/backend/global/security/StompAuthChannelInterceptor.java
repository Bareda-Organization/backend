package src.backend.global.security;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.access.AcademyScope;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;

/**
 * STOMP CONNECT 프레임에서 한 번만 인증한다(REST 의 매 요청 검증과 다르게, 세션 수립 시 1회).
 * 이후 같은 세션의 모든 메시지는 CONNECT 때 심어둔 Principal(AuthUser)을 그대로 사용한다.
 *
 * <p>SUBSCRIBE 는 목적지별로 추가 인가가 필요할 때만 검사한다 — 개인 큐(/user/queue/**)는
 * Spring 의 user-destination 라우팅이 "본인 세션에만" 배달을 구조적으로 보장해 검사가 필요 없고,
 * 관리자용 테넌트 브로드캐스트 토픽(/topic/tenant/{tenantId}/**)만 소속 학원인지 확인한다(Phase 3d).
 *
 * <p>학원 대조는 {@link AcademyScope} 하나가 판정한다 — 여기에 직접 쓰면 메인 관리자를 거부하는 등
 * REST 경로와 규칙이 갈린다(실제로 그랬다). {@code /topic/tenant/...} 경로의 옛 어휘는 클라이언트
 * 계약이라 Phase 10 에서 바꾼다.
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";
    private static final Pattern TENANT_TOPIC_PATTERN = Pattern.compile("^/topic/tenant/(\\d+)/.*");

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
        } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            authorizeTenantTopicSubscribe(accessor);
        }
        return message;
    }

    private void authorizeTenantTopicSubscribe(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null) {
            return;
        }
        Matcher matcher = TENANT_TOPIC_PATTERN.matcher(destination);
        if (!matcher.matches()) {
            return; // 테넌트 브로드캐스트 토픽이 아니면(개인 큐 등) 이 검사 대상이 아니다.
        }
        if (!(accessor.getUser() instanceof AuthUser subscriber)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        AcademyScope.assertAccessible(subscriber, Long.valueOf(matcher.group(1)));
    }
}
