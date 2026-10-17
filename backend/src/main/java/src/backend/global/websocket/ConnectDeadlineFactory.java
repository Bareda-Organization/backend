package src.backend.global.websocket;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
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
 * 연결을 열고 STOMP {@code CONNECT} 를 보내지 않는 소켓을 마감 시간 뒤 {@code 1008}(정책 위반)로 닫는다(Ruling 899 D). 인증 전
 * 소켓은 서버 쪽 세션·스레드 자원을 쥐면서 아무 권한도 증명하지 않았다 — 열어 두기만 하는 연결이 풀을 채우는 길을 막는다. 마감은 인증된 CONNECT 를
 * 마쳤는지({@link StompSessionExpiry#isConnected})로 판정하고, 세션이 먼저 닫히면 예약을 취소한다. 예약은 하트비트 스케줄러를 쓴다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ConnectDeadlineFactory implements WebSocketHandlerDecoratorFactory {

    private final StompSessionExpiry sessionExpiry;
    @Qualifier("heartbeatScheduler")
    private final ObjectProvider<TaskScheduler> scheduler;
    private final MeterRegistry meterRegistry;
    /** CONNECT 마감(ms) — 사양 값이 10초다(API_SPEC §7.2). 시험이 줄여 쓸 수 있게 속성으로 둔다. */
    @Value("${app.ws.connect-deadline-ms:10000}")
    private final long deadlineMs;

    private final Map<String, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();

    @Override
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                pending.put(session.getId(), scheduler.getObject()
                        .schedule(() -> closeIfNotConnected(session), java.time.Instant.now().plus(Duration.ofMillis(deadlineMs))));
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                ScheduledFuture<?> future = pending.remove(session.getId());
                if (future != null) {
                    future.cancel(false);
                }
                super.afterConnectionClosed(session, closeStatus);
            }
        };
    }

    private void closeIfNotConnected(WebSocketSession session) {
        pending.remove(session.getId());
        if (!session.isOpen() || sessionExpiry.isConnected(session.getId())) {
            return;
        }
        try {
            meterRegistry.counter("schoolbus.ws.closed", "reason", "CONNECT_TIMEOUT").increment();
            session.close(WebSocketCloseCodes.CONNECT_TIMEOUT);
        } catch (Exception e) {
            log.warn("CONNECT 마감을 넘긴 소켓을 닫지 못했다 — sessionId={}", session.getId(), e);
        }
    }
}
