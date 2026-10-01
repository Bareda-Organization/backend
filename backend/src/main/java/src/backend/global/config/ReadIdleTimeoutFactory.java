package src.backend.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

import jakarta.websocket.Session;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 클라이언트가 유휴 제한 동안 <b>아무 프레임도 보내지 않은</b> WebSocket 세션을 컨테이너(Tomcat)가 닫게 한다(R46-CONNCAP, Ruling 693).
 *
 * <p>STOMP 하트비트를 협상한 세션은 단순 브로커가 무수신 30초에 치운다. 구멍은 {@code heart-beat:0,0} 으로 붙은 클라이언트다 — 읽기
 * 감시가 꺼져 조용히 사라져도(전원·망 끊김으로 FIN 도 못 보낸 경우) 서버는 다음 쓰기가 실패할 때까지 세션·구독·우리 쪽 맵 둘을 쥔다.
 *
 * <p>표준 {@code maxSessionIdleTimeout} 이 아니라 Tomcat 의 <b>읽기 전용</b> 유휴 속성({@code READ_IDLE_TIMEOUT_MS})을 쓰는 이유 —
 * 표준 값은 읽기·쓰기가 <b>둘 다</b> 제한을 넘을 때만 닫는데(Tomcat 11.0.22 {@code WsSession#checkExpiration}), 서버가 방송을 계속 쓰면
 * 쓰기 시각이 갱신돼 사라진 클라이언트가 영영 안 걸린다. 읽기 전용 값은 서버 쓰기와 무관하게 "클라이언트 생존 증명이 없었는가" 만 본다.
 * 정상 클라이언트는 하트비트 줄바꿈을 10초마다 보내(양방향 10초 · API_SPEC §7.2) 읽기 시각이 늘 갱신되므로 걸리지 않는다. 판정은 Tomcat 이
 * 10초마다 하므로 닫히는 시각은 제한 + 최대 10초다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReadIdleTimeoutFactory implements WebSocketHandlerDecoratorFactory {

    /** Tomcat 이 세션 속성에서 읽는 읽기 유휴 제한 키 — {@code org.apache.tomcat.websocket.Constants#READ_IDLE_TIMEOUT_MS}. 값은 {@code Long} 이어야 한다. */
    static final String TOMCAT_READ_IDLE_TIMEOUT_MS = "org.apache.tomcat.websocket.READ_IDLE_TIMEOUT_MS";

    /** 클라이언트 무수신 제한(ms). 운영 60초 — 하트비트 10초의 6배라 한 번 늦거나 빠져도 안 걸리고, 하트비트 감시(30초) 뒤의 안전망이다. */
    @Value("${app.ws.idle-timeout-ms}")
    private final long idleTimeoutMs;

    @Override
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                applyTo(session);
                super.afterConnectionEstablished(session);
            }
        };
    }

    private void applyTo(WebSocketSession session) {
        Session tomcatSession = session instanceof NativeWebSocketSession nativeSession
                ? nativeSession.getNativeSession(Session.class) : null;
        if (tomcatSession == null) {
            log.warn("WebSocket 유휴 제한을 걸지 못했다 — Tomcat 세션이 아니다(sessionId={})", session.getId());
            return;
        }
        tomcatSession.getUserProperties().put(TOMCAT_READ_IDLE_TIMEOUT_MS, idleTimeoutMs);
    }
}
