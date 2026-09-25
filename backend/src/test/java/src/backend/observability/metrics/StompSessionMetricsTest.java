package src.backend.observability.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class StompSessionMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final StompSessionMetrics metrics = new StompSessionMetrics(registry);

    @Test
    void startsAtZero() {
        assertThat(sessions()).isEqualTo(0.0d);
    }

    @Test
    void connect_increments_disconnect_decrements() {
        metrics.onConnected(connectedEvent("session-id"));
        metrics.onConnected(connectedEvent("other-session"));
        assertThat(sessions()).isEqualTo(2.0d);

        metrics.onDisconnected(disconnectEvent());
        assertThat(sessions()).isEqualTo(1.0d);
    }

    /** 해제 이벤트가 중복으로 와도 음수로 내려가지 않는다 — 음수 게이지는 대시보드를 읽을 수 없게 만든다. */
    @Test
    void neverGoesNegative() {
        metrics.onDisconnected(disconnectEvent());
        metrics.onDisconnected(disconnectEvent());
        assertThat(sessions()).isEqualTo(0.0d);
    }

    /**
     * 연결된 적 없는 세션의 해제(CONNECT 가 인터셉터에서 거부된 소켓)와 같은 세션의 중복 해제는 다른 정상 세션 몫을
     * 깎지 않는다(BR-128) — 세션 ID 로 연결·해제를 짝지어 센다.
     */
    @Test
    void 연결된_적_없는_세션과_중복_해제는_세지_않는다() {
        metrics.onConnected(connectedEvent("live"));

        metrics.onDisconnected(new SessionDisconnectEvent(this, mock(Message.class), "rejected", null));
        assertThat(sessions()).as("거부된 연결의 해제가 정상 세션을 깎았다").isEqualTo(1.0d);

        metrics.onDisconnected(new SessionDisconnectEvent(this, mock(Message.class), "live", null));
        metrics.onConnected(connectedEvent("other"));
        metrics.onDisconnected(new SessionDisconnectEvent(this, mock(Message.class), "live", null));
        assertThat(sessions()).as("같은 세션의 중복 해제가 다른 세션을 깎았다").isEqualTo(1.0d);
    }

    private SessionConnectedEvent connectedEvent(String sessionId) {
        return new SessionConnectedEvent(this, MessageBuilder.withPayload(new byte[0])
                .setHeader(SimpMessageHeaderAccessor.SESSION_ID_HEADER, sessionId)
                .build());
    }

    private double sessions() {
        return registry.get("schoolbus.stomp.sessions").gauge().value();
    }

    @SuppressWarnings("unchecked")
    private SessionDisconnectEvent disconnectEvent() {
        return new SessionDisconnectEvent(this, mock(Message.class), "session-id", null);
    }
}
