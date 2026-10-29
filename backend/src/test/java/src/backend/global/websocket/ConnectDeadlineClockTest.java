package src.backend.global.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import src.backend.global.security.StompSessionExpiry;

/** CONNECT 마감 시각은 주입한 시계에서 계산한다 — 시스템 시계를 직접 읽으면 시계를 고정한 시험에서 마감이 어긋난다. */
class ConnectDeadlineClockTest {

    private static final Instant NOW = Instant.parse("2030-04-01T00:00:00Z");

    @Test
    @DisplayName("연결을 연 순간 예약하는 마감 시각은 주입한 시계의 현재 시각에 마감 시간을 더한 값이다")
    @SuppressWarnings("unchecked")
    void 마감_시각은_주입한_시계에서_계산한다() throws Exception {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ObjectProvider<TaskScheduler> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(scheduler);
        ConnectDeadlineFactory factory = new ConnectDeadlineFactory(mock(StompSessionExpiry.class), provider,
                new SimpleMeterRegistry(), 10_000L, Clock.fixed(NOW, ZoneOffset.UTC));
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("session-1");

        WebSocketHandler decorated = factory.decorate(mock(WebSocketHandler.class));
        decorated.afterConnectionEstablished(session);

        ArgumentCaptor<Instant> deadline = ArgumentCaptor.forClass(Instant.class);
        verify(scheduler).schedule(any(Runnable.class), deadline.capture());
        assertThat(deadline.getValue()).isEqualTo(NOW.plusSeconds(10));
    }
}
