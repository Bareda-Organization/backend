package src.backend.global.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

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

/**
 * 방송이 <b>실제로 선 위에 나가는 JSON</b> 의 키 모양(BR-104).
 *
 * <p>다른 방송 시험은 송신 템플릿을 가짜로 두고 시험이 직접 만든 SNAKE_CASE 매퍼로 직렬화해 본다 —
 * "키가 snake_case 인가" 가 시험 안에서 참으로 만들어진 뒤 검사되는 형태라, STOMP 메시지 변환기가
 * 앱 매퍼 대신 기본 매퍼를 쓰게 돼도(앱 3종이 받는 키가 전부 camelCase 로 바뀌어도) 초록이다.
 * 여기서는 원시 WebSocket 으로 구독해 MESSAGE 프레임 본문을 그대로 읽는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketWireFormatTest {

    private static final String DESTINATION = "/topic/academy/1/live";
    private static final char NULL_TERMINATOR = '\0';

    @LocalServerPort
    private int port;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private WebSocketBroadcastGateway gateway;

    /** camelCase 자바 필드가 둘 이상 단어인 payload — 변환기가 네이밍 전략을 쓰는지 드러난다. */
    private record Probe(String currentStopName, Long nextStopId) {
    }

    @Test
    void 방송_봉투와_payload_키는_snake_case_로_나간다() throws Exception {
        JsonNode body = new ObjectMapper().readTree(receiveOneBroadcast());

        assertThat(body.has("run_id")).as("봉투 키: " + body).isTrue();
        assertThat(body.has("occurred_at")).as("봉투 키: " + body).isTrue();
        assertThat(body.path("event").asText()).isEqualTo("wire_probe");
        assertThat(body.path("payload").has("current_stop_name")).as("payload 키: " + body).isTrue();
        assertThat(body.path("payload").has("next_stop_id")).as("payload 키: " + body).isTrue();
        assertThat(body.has("runId")).isFalse();
    }

    /** 관제 채널을 구독한 뒤, 구독이 성립할 때까지 게이트웨이로 표식 방송을 반복해 첫 본문을 돌려준다. */
    private String receiveOneBroadcast() throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        WebSocketSession session = new StandardWebSocketClient()
                .execute(new FrameCollector(received), null, URI.create("ws://localhost:" + port + "/ws/location"))
                .get(5, TimeUnit.SECONDS);
        try {
            String token = tokenProvider.createAccessToken(2L, 1L, Role.STAFF, AccountStatus.ACTIVE);
            session.sendMessage(new TextMessage("CONNECT\naccept-version:1.2\nhost:localhost\nAuthorization:Bearer "
                    + token + "\n\n" + NULL_TERMINATOR));
            assertThat(received.poll(5, TimeUnit.SECONDS)).startsWith("CONNECTED");
            session.sendMessage(new TextMessage("SUBSCRIBE\nid:sub-0\ndestination:" + DESTINATION + "\n\n"
                    + NULL_TERMINATOR));
            for (int attempt = 0; attempt < 25; attempt++) {
                gateway.send(DESTINATION, "wire_probe", 7L, OffsetDateTime.now(), new Probe("정문", 3L));
                String frame = received.poll(200, TimeUnit.MILLISECONDS);
                if (frame != null) {
                    assertThat(frame).startsWith("MESSAGE");
                    return frame.substring(frame.indexOf("\n\n") + 2, frame.lastIndexOf(NULL_TERMINATOR));
                }
            }
            throw new AssertionError("방송이 도착하지 않았다");
        } finally {
            session.close();
        }
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
