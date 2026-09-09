package src.backend.observability.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
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
        metrics.onConnected(connectedEvent());
        metrics.onConnected(connectedEvent());
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

    private double sessions() {
        return registry.get("schoolbus.stomp.sessions").gauge().value();
    }

    @SuppressWarnings("unchecked")
    private SessionConnectedEvent connectedEvent() {
        return new SessionConnectedEvent(this, mock(Message.class));
    }

    @SuppressWarnings("unchecked")
    private SessionDisconnectEvent disconnectEvent() {
        return new SessionDisconnectEvent(this, mock(Message.class), "session-id", null);
    }
}
