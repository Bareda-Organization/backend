package src.backend.global.websocket;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

import io.micrometer.core.instrument.MeterRegistry;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import src.backend.global.security.StompSessionExpiry;
import src.backend.global.security.WebSocketCloseCodes;

/**
 * 열린 WebSocket 세션을 세션 id 로 들고 있다가 계정 단위로 닫는다. STOMP 쪽({@link StompSessionExpiry})은 계정의 세션 id 만 알고
 * 세션 객체는 전송 계층이 쥐고 있어, 데코레이터로 세션이 열리고 닫힐 때 맵을 맞춘다. 원본(Tomcat) 세션을 받도록 {@code WebSocketConfig} 가 가장 마지막에 등록한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OpenWebSocketSessions implements WebSocketHandlerDecoratorFactory {

    private final StompSessionExpiry sessionExpiry;
    private final MeterRegistry meterRegistry;
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    @Override
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sessions.put(session.getId(), session);
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                sessions.remove(session.getId());
                super.afterConnectionClosed(session, closeStatus);
            }
        };
    }

    /** 이 계정들이 연 열린 세션을 모두 {@code 4500 SESSION_REVOKED} 로 닫는다 — 닫은 세션 수를 돌려준다. */
    public int revoke(Collection<Long> accountIds) {
        int closed = 0;
        for (String sessionId : sessionExpiry.sessionIdsOf(accountIds)) {
            WebSocketSession session = sessions.get(sessionId);
            if (session == null || !session.isOpen()) {
                continue;
            }
            try {
                meterRegistry.counter("schoolbus.ws.closed", "reason", "4500").increment(); // 닫기 전에 센다 — 닫힌 뒤 값을 읽는 쪽이 앞서지 않게
                session.close(WebSocketCloseCodes.SESSION_REVOKED);
                closed++;
            } catch (IOException | RuntimeException e) {
                log.warn("권한이 바뀐 계정의 WebSocket 세션을 닫지 못했다 — sessionId={}", sessionId, e);
            }
        }
        return closed;
    }

    /** 열린 세션을 모두 {@code 1012} 로 닫는다(서버 종료) — 닫은 세션 수를 돌려준다. 하나가 실패해도 나머지는 계속 닫는다. */
    public int closeAllForRestart() {
        int closed = 0;
        for (WebSocketSession session : List.copyOf(sessions.values())) {
            if (!session.isOpen()) {
                continue;
            }
            try {
                meterRegistry.counter("schoolbus.ws.closed", "reason", "1012").increment();
                session.close(CloseStatus.SERVICE_RESTARTED);
                closed++;
            } catch (IOException | RuntimeException e) {
                log.warn("종료 때 WebSocket 세션을 닫지 못했다 — sessionId={}", session.getId(), e);
            }
        }
        return closed;
    }
}
