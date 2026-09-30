package src.backend.global.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 위치 방송은 <b>구독자가 있는 채널에만</b> 직렬화·전송한다(R46 D #6) — 위치 1건이 채널 22개(학생 20 · 학원 1 · 관리자 1)로
 * 나가는데 학부모가 보고 있는 것은 그중 일부라, 구독자 없는 채널까지 JSON 으로 만들면 2초마다 100대 × 22회가 낭비다.
 * 구독자가 있는 채널은 반드시 받는다 — 빠지면 학부모 지도가 멈춘다. 원시 WebSocket 으로 실제 구독한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PositionSubscriberFilterTest {

    private static final String SUBSCRIBED = "/topic/academy/1/live";
    private static final String NOT_SUBSCRIBED = "/topic/students/987654/run";
    private static final char NULL_TERMINATOR = '\0';

    @LocalServerPort
    private int port;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private WebSocketBroadcastGateway gateway;

    @MockitoSpyBean
    private SimpMessagingTemplate messagingTemplate;

    @Test
    @DisplayName("구독자가 있는 채널의 위치 방송은 반드시 도착한다")
    void 구독자_있는_채널은_위치를_받는다() throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        WebSocketSession session = new StandardWebSocketClient()
                .execute(new FrameCollector(received), null, URI.create("ws://localhost:" + port + "/ws/location"))
                .get(5, TimeUnit.SECONDS);
        try {
            String token = tokenProvider.createAccessToken(2L, 1L, Role.STAFF, AccountStatus.ACTIVE);
            session.sendMessage(new TextMessage("CONNECT\naccept-version:1.2\nhost:localhost\nAuthorization:Bearer "
                    + token + "\n\n" + NULL_TERMINATOR));
            assertThat(received.poll(5, TimeUnit.SECONDS)).startsWith("CONNECTED");
            session.sendMessage(new TextMessage("SUBSCRIBE\nid:sub-0\ndestination:" + SUBSCRIBED + "\n\n"
                    + NULL_TERMINATOR));

            String frame = null;
            for (int attempt = 0; attempt < 25 && frame == null; attempt++) {
                gateway.send(SUBSCRIBED, "position", 7L, OffsetDateTime.now(), new Object());
                frame = received.poll(200, TimeUnit.MILLISECONDS);
            }

            assertThat(frame).as("구독한 채널의 위치 방송이 도착해야 한다 — 빠지면 학부모 지도가 멈춘다").startsWith("MESSAGE");
        } finally {
            session.close();
        }
    }

    @Test
    @DisplayName("구독자 없는 채널의 위치 방송은 직렬화·전송 자체를 하지 않는다")
    void 구독자_없는_채널은_위치를_보내지_않는다() {
        gateway.send(NOT_SUBSCRIBED, "position", 7L, OffsetDateTime.now(), new Object());

        verify(messagingTemplate, never()).convertAndSend(eq(NOT_SUBSCRIBED), any(Object.class));
    }

    private record FrameCollector(BlockingQueue<String> received) implements WebSocketHandler {

        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
        }

        @Override
        public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {
            if (message instanceof TextMessage text && !text.getPayload().isBlank()) {
                received.add(text.getPayload());
            }
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) {
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        }

        @Override
        public boolean supportsPartialMessages() {
            return false;
        }
    }
}
