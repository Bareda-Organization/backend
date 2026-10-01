package src.backend.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

/**
 * 실서버 시험용 원시 STOMP 클라이언트 — 하트비트 헤더를 마음대로 정하고, 보내라고 하지 않는 한 아무것도 보내지 않는다.
 *
 * <p>Spring·stompjs 클라이언트는 협상한 하트비트를 알아서 보내 "조용한 클라이언트" 를 만들 수 없다. 여기서는 {@code CONNECT} 한 번
 * 뒤로 프레임을 직접 보낼 때만 나간다(하트비트는 줄바꿈 한 줄).
 */
final class RawStompClient implements AutoCloseable {

    private static final char NULL_TERMINATOR = '\0';

    private final WebSocketSession session;
    private final CountDownLatch closed;

    private RawStompClient(WebSocketSession session, CountDownLatch closed) {
        this.session = session;
        this.closed = closed;
    }

    /** 소켓을 열고 {@code CONNECT}(하트비트 헤더 = {@code heartBeat})를 보내 {@code CONNECTED} 를 받을 때까지 기다린다. */
    static RawStompClient connect(int port, String token, String heartBeat) throws Exception {
        BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        CountDownLatch closed = new CountDownLatch(1);
        WebSocketSession session = new StandardWebSocketClient()
                .execute(new Collector(frames, closed), null, URI.create("ws://localhost:" + port + "/ws/location"))
                .get(5, TimeUnit.SECONDS);
        session.sendMessage(new TextMessage("CONNECT\naccept-version:1.2\nhost:localhost\nheart-beat:" + heartBeat
                + "\nAuthorization:Bearer " + token + "\n\n" + NULL_TERMINATOR));
        assertThat(frames.poll(5, TimeUnit.SECONDS)).as("CONNECTED 프레임").startsWith("CONNECTED");
        return new RawStompClient(session, closed);
    }

    /** 목적지를 구독한다 — 서버가 이 세션에 방송을 계속 쓰게 해, 쓰기 활동이 있어도 유휴 제한이 걸리는지 보는 데 쓴다. */
    void subscribe(String destination) throws IOException {
        session.sendMessage(new TextMessage("SUBSCRIBE\nid:sub-0\ndestination:" + destination + "\n\n" + NULL_TERMINATOR));
    }

    /** 클라이언트 하트비트 — 줄바꿈 한 줄. 서버는 이것을 "아직 살아 있다" 는 읽기로 센다. */
    void sendHeartbeat() throws IOException {
        session.sendMessage(new TextMessage("\n"));
    }

    /** 서버가 소켓을 닫을 때까지 {@code timeout} 안에 닫혔으면 true. */
    boolean awaitClosed(Duration timeout) throws InterruptedException {
        return closed.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    boolean isOpen() {
        return session.isOpen();
    }

    @Override
    public void close() throws IOException {
        session.close();
    }

    private record Collector(BlockingQueue<String> frames, CountDownLatch closed) implements WebSocketHandler {

        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
        }

        @Override
        public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {
            if (message instanceof TextMessage text && !text.getPayload().isBlank()) {
                frames.add(text.getPayload());
            }
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) {
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closed.countDown();
        }

        @Override
        public boolean supportsPartialMessages() {
            return false;
        }
    }
}
