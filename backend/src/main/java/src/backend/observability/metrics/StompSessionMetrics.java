package src.backend.observability.metrics;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 활성 STOMP 세션 수를 센다.
 *
 * <p>기존 {@code LocationSessionRegistry} 는 "끊긴 학생과 끊긴 시각"만 보관해 활성 수를 알 수 없다.
 * 그 클래스의 책임(연결끊김 유예 판정의 근거)을 넓히지 않고, 여기서 Spring 이 발행하는 연결·해제
 * 이벤트를 따로 센다.
 *
 * <p>세션 수는 실시간 push 파이프라인이 실제로 쓰이는지를 보여준다 — 0 이 지속되면
 * 관제·학부모 화면이 아무도 구독하지 않는 상태다.
 */
@Component
public class StompSessionMetrics {

    private final AtomicInteger activeSessions = new AtomicInteger();

    public StompSessionMetrics(MeterRegistry registry) {
        Gauge.builder("schoolbus.stomp.sessions", activeSessions, AtomicInteger::get)
                .description("활성 STOMP 세션 수")
                .register(registry);
    }

    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        activeSessions.incrementAndGet();
    }

    /** 해제 이벤트가 중복으로 와도 0 아래로 내려가지 않게 막는다 — 음수 게이지는 대시보드를 읽을 수 없게 만든다. */
    @EventListener
    public void onDisconnected(SessionDisconnectEvent event) {
        activeSessions.updateAndGet(current -> current > 0 ? current - 1 : 0);
    }
}
