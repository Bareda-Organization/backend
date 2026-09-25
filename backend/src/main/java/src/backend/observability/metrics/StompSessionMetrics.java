package src.backend.observability.metrics;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
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

    /**
     * 연결된 세션 ID — 개수를 올리고 내리는 대신 집합으로 짝지어 센다(BR-128). CONNECT 가 인터셉터에서 거부된 소켓은
     * 연결 이벤트 없이 해제 이벤트만 오고, 해제 이벤트는 중복 발행될 수 있어 단순 감소는 다른 세션 몫을 깎는다.
     */
    private final Set<String> activeSessionIds = ConcurrentHashMap.newKeySet();

    public StompSessionMetrics(MeterRegistry registry) {
        Gauge.builder("schoolbus.stomp.sessions", activeSessionIds, Set::size)
                .description("활성 STOMP 세션 수")
                .register(registry);
    }

    /** STOMP CONNECT 성공 세션 ID 를 활성 집합에 더한다. */
    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        String sessionId = SimpMessageHeaderAccessor.getSessionId(event.getMessage().getHeaders());
        if (sessionId != null) {
            activeSessionIds.add(sessionId);
        }
    }

    /** 연결된 적 없는 세션·이미 해제된 세션의 해제는 아무것도 바꾸지 않는다 — 음수·과소 계수를 함께 막는다. */
    @EventListener
    public void onDisconnected(SessionDisconnectEvent event) {
        activeSessionIds.remove(event.getSessionId());
    }
}
