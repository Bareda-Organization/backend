package src.backend.global.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import io.micrometer.core.instrument.MeterRegistry;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * 접속 · 구독 거절과 종료 사유별 카운터 — {@code schoolbus.ws.rejected{phase,reason}} 와 {@code schoolbus.ws.closed{reason}}
 * ({@code 4403} · {@code TOKEN_EXPIRED} · {@code 4500} · {@code CONNECT_TIMEOUT})가 실제 거절 뒤에 오르고 Prometheus 출력에 나온다. 시험 하나는 다음도 잰다:
 * ERROR 프레임(TOKEN_EXPIRED) 뒤 서버가 실제로 쓰는 종료 코드.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketRejectionMetricsTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final long PARENT_ACCOUNT = 6L;
    private static final char NULL_TERMINATOR = '\0';

    /** ERROR 프레임 뒤 Spring 이 세션을 닫는 코드({@code CloseStatus.PROTOCOL_ERROR} = 1002). */
    private static final int CLOSE_CODE_AFTER_ERROR_FRAME = 1002;

    @LocalServerPort
    private int port;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void restore() {
        jdbcTemplate.update("UPDATE account SET status = 'active', status_before_block = NULL, blocked_at = NULL, "
                + "block_reason = NULL WHERE id = ?", PARENT_ACCOUNT);
    }

    @Test
    void 금지된_구독은_구독_거절과_4403_종료로_센다() throws Exception {
        double rejectedBefore = rejected("subscribe", "FORBIDDEN");
        double closedBefore = closed("4403");

        Session session = Session.open(port);
        session.connect(token());
        session.send("SUBSCRIBE", "id:sub-0", "destination:/topic/students/999999/run");

        assertThat(session.awaitFrame()).startsWith("ERROR").contains("message:FORBIDDEN");
        assertThat(session.closeCode()).isEqualTo(4403);
        assertThat(rejected("subscribe", "FORBIDDEN")).isEqualTo(rejectedBefore + 1);
        assertThat(closed("4403")).isEqualTo(closedBefore + 1);
        assertPrometheusLine("schoolbus_ws_rejected_total", "phase=\"subscribe\"", "reason=\"FORBIDDEN\"");
        assertPrometheusLine("schoolbus_ws_closed_total", "reason=\"4403\"");
    }

    @Test
    void 권한을_잃은_계정의_접속은_접속_거절과_TOKEN_EXPIRED_종료로_세고_ERROR_뒤_종료_코드는_1002_다() throws Exception {
        jdbcTemplate.update("UPDATE account SET status = 'blocked', status_before_block = 'active', "
                + "blocked_at = now(), block_reason = '시험' WHERE id = ?", PARENT_ACCOUNT);
        double rejectedBefore = rejected("connect", "TOKEN_EXPIRED");
        double closedBefore = closed("TOKEN_EXPIRED");

        Session session = Session.open(port);
        session.send("CONNECT", "accept-version:1.2", "host:localhost", "Authorization:Bearer " + token());

        assertThat(session.awaitFrame()).startsWith("ERROR").contains("message:TOKEN_EXPIRED");
        assertThat(session.closeCode()).as("ERROR 프레임 뒤 서버가 쓰는 종료 코드").isEqualTo(CLOSE_CODE_AFTER_ERROR_FRAME);
        assertThat(rejected("connect", "TOKEN_EXPIRED")).isEqualTo(rejectedBefore + 1);
        assertThat(closed("TOKEN_EXPIRED")).isEqualTo(closedBefore + 1);
        assertPrometheusLine("schoolbus_ws_rejected_total", "phase=\"connect\"", "reason=\"TOKEN_EXPIRED\"");
        assertPrometheusLine("schoolbus_ws_closed_total", "reason=\"TOKEN_EXPIRED\"");
    }

    private double rejected(String phase, String reason) {
        return meterRegistry.counter("schoolbus.ws.rejected", "phase", phase, "reason", reason).count();
    }

    private double closed(String reason) {
        return meterRegistry.counter("schoolbus.ws.closed", "reason", reason).count();
    }

    private String token() {
        return tokenProvider.createAccessToken(PARENT_ACCOUNT, 1L, Role.PARENT, AccountStatus.ACTIVE);
    }

    /** 지표 이름으로 시작하고 태그 조각을 모두 가진 줄이 Prometheus 출력에 있어야 한다 — 공통 태그(application) 때문에 태그 순서를 고정하지 않는다. */
    private void assertPrometheusLine(String metric, String... labelFragments) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(java.util.List.of(MediaType.ALL));
        String body = new TestRestTemplate().exchange("http://localhost:" + port + "/actuator/prometheus", HttpMethod.GET,
                new HttpEntity<>(headers), String.class).getBody();
        assertThat(body.lines())
                .as("%s 줄(태그 %s)이 Prometheus 출력에 있어야 한다", metric, java.util.List.of(labelFragments))
                .anyMatch(line -> line.startsWith(metric + "{")
                        && java.util.Arrays.stream(labelFragments).allMatch(line::contains));
    }

    /** 프레임과 종료 코드를 모으는 원시 STOMP 세션. */
    private static final class Session implements WebSocketHandler {

        private final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        private final CompletableFuture<Integer> closeCode = new CompletableFuture<>();
        private WebSocketSession session;

        static Session open(int port) throws Exception {
            Session client = new Session();
            client.session = new StandardWebSocketClient()
                    .execute(client, null, URI.create("ws://localhost:" + port + "/ws/location"))
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return client;
        }

        void connect(String token) throws Exception {
            send("CONNECT", "accept-version:1.2", "host:localhost", "Authorization:Bearer " + token);
            assertThat(awaitFrame()).startsWith("CONNECTED");
        }

        void send(String command, String... headers) throws Exception {
            session.sendMessage(new TextMessage(command + "\n" + String.join("\n", headers) + "\n\n" + NULL_TERMINATOR));
        }

        String awaitFrame() throws InterruptedException {
            String frame = frames.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(frame).as("%d초 안에 프레임이 오지 않았다", TIMEOUT_SECONDS).isNotNull();
            return frame;
        }

        int closeCode() throws Exception {
            return closeCode.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

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
            closeCode.complete(status.getCode());
        }

        @Override
        public boolean supportsPartialMessages() {
            return false;
        }
    }
}
