package src.backend.global.security;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import lombok.RequiredArgsConstructor;

import src.backend.global.error.ErrorCode;

/**
 * STOMP 세션은 그것을 연 access 토큰보다 오래 살지 않는다(BR-083).
 *
 * <p>인증은 CONNECT 에서 한 번뿐이라, 퇴사·차단(refresh 무효화)이나 토큰 만료 뒤에도 열린 세션이 관제·자녀
 * 채널 방송을 계속 받았다. REST 는 access 토큰 만료(15분)로 권한이 회수되므로 세션도 같은 시각에 끊는다 —
 * 다시 붙으려면 새 토큰이 필요하고, 그 재발급이 퇴사·차단에서 막힌다.
 *
 * <p>출력 채널 인터셉터로 둔다 — 만료 뒤 첫 방송을 {@code TOKEN_EXPIRED} ERROR 프레임으로 바꿔 보내면
 * Spring 이 ERROR 뒤 세션을 닫는다({@code StompSubProtocolHandler#sendToClient}). 방송 메시지 헤더에는 세션
 * 속성이 실리지 않아 세션 id 로 만료 시각을 들고 있고, 연결이 끊기면 지운다.
 */
@Component
@RequiredArgsConstructor
public class StompSessionExpiry implements ChannelInterceptor {

    private final Map<String, Instant> expiresAtBySession = new ConcurrentHashMap<>();

    private final Clock clock;

    /** CONNECT 인증이 끝난 세션의 토큰 만료 시각을 남긴다. */
    void record(String sessionId, Instant expiresAt) {
        expiresAtBySession.put(sessionId, expiresAt);
    }

    boolean isExpired(String sessionId) {
        Instant expiresAt = sessionId == null ? null : expiresAtBySession.get(sessionId);
        return expiresAt != null && !Instant.now(clock).isBefore(expiresAt);
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        String sessionId = SimpMessageHeaderAccessor.getSessionId(message.getHeaders());
        if (SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) != SimpMessageType.MESSAGE
                || !isExpired(sessionId)) {
            return message;
        }
        expiresAtBySession.remove(sessionId);
        StompHeaderAccessor error = StompHeaderAccessor.create(StompCommand.ERROR);
        error.setMessage(ErrorCode.TOKEN_EXPIRED.name());
        error.setSessionId(sessionId);
        return MessageBuilder.createMessage(new byte[0], error.getMessageHeaders());
    }

    @EventListener
    public void forget(SessionDisconnectEvent event) {
        expiresAtBySession.remove(event.getSessionId());
    }
}
