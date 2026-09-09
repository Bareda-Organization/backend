package src.backend.global.config;

import java.io.IOException;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;
import org.springframework.web.socket.handler.WebSocketSessionDecorator;

import src.backend.global.security.WebSocketCloseCodes;

/**
 * SUBSCRIBE 인가 실패의 종료 코드를 4403 으로 바꾼다(목표 7, API_SPEC §7) — Spring 은 ERROR 프레임을
 * 보낸 뒤 {@code session.close(CloseStatus.PROTOCOL_ERROR)} 를 하드코딩해서 부른다
 * ({@code StompSubProtocolHandler#sendErrorMessage}·{@code #sendToClient} 의 finally 블록, Spring
 * 7.0.8 확인). 커스텀 {@code StompSubProtocolErrorHandler}({@link StompErrorFrameHandler})는 프레임
 * <b>내용</b>만 바꿀 뿐 이 close 호출 자체를 가로채지 못해, 세션을 감싸 {@code close(CloseStatus)} 를
 * 직접 가로채는 것이 유일한 확장점이다({@link WebSocketTransportRegistration#addDecoratorFactory}).
 *
 * <p>{@link StompAuthChannelInterceptor}(메시지 계층)가 SUBSCRIBE 거부 시 세션 속성에 남긴 표식을
 * 이 데코레이터(전송 계층)가 읽는다 — {@code WebSocketSession#getAttributes()} 가 STOMP 세션 속성과
 * 같은 맵이라(SimpAttributes 위임) 스레드 로컬 없이 계층을 넘나든다.
 *
 * <p>{@code afterConnectionEstablished} 에서 한 번만 감싸도 이후 모든 프레임에 적용되는 이유 —
 * {@code SubProtocolWebSocketHandler} 가 이 메서드로 받은 세션을 자신의 내부 맵에 저장해두고, 그 뒤
 * {@code handleMessage(session, ...)} 로 들어오는 세션 인자를 무시한 채 <b>저장해둔 세션</b>으로
 * 바꿔 쓴다({@code SubProtocolWebSocketHandler#handleMessage} 의 {@code session = holder.getSession()}).
 * 따라서 최초 1회의 치환이 연결 전체 수명에 걸쳐 유지된다.
 */
@Component
public class ForbiddenSubscriptionCloseFactory implements WebSocketHandlerDecoratorFactory {

    @Override
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                super.afterConnectionEstablished(new CloseCodeOverridingSession(session));
            }
        };
    }

    private static final class CloseCodeOverridingSession extends WebSocketSessionDecorator {

        private CloseCodeOverridingSession(WebSocketSession delegate) {
            super(delegate);
        }

        @Override
        public void close(CloseStatus status) throws IOException {
            if (CloseStatus.PROTOCOL_ERROR.equals(status) && isMarkedForbidden()) {
                super.close(WebSocketCloseCodes.SUBSCRIPTION_FORBIDDEN);
                return;
            }
            super.close(status);
        }

        private boolean isMarkedForbidden() {
            return Boolean.TRUE.equals(getAttributes().get(WebSocketCloseCodes.FORBIDDEN_SUBSCRIPTION_ATTR));
        }
    }
}
