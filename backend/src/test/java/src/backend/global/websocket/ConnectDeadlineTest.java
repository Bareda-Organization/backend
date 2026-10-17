package src.backend.global.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import io.micrometer.core.instrument.MeterRegistry;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * 소켓만 열고 STOMP {@code CONNECT} 를 보내지 않으면 10초 뒤 서버가 {@code 1008} 로 닫는다(R57 RT-8 · K1 ④). 마감 시간 기본값(10초)을 그대로 쓴다 —
 * 대조군으로 같은 시간 안에 CONNECT 를 보낸 소켓은 닫히지 않아야 "무조건 닫는" 구현이 통과하지 못한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConnectDeadlineTest {

    private static final long CLOSE_WAIT_SECONDS = 14;
    private static final char NULL_TERMINATOR = '\0';

    @LocalServerPort
    private int port;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void CONNECT_없는_소켓은_10초_뒤_1008_로_닫히고_CONNECT_한_소켓은_닫히지_않는다() throws Exception {
        double timedOutBefore = meterRegistry.counter("schoolbus.ws.closed", "reason", "CONNECT_TIMEOUT").count();
        CloseWatcher silent = open();
        CloseWatcher connected = open();
        String token = tokenProvider.createAccessToken(2L, 1L, Role.STAFF, AccountStatus.ACTIVE);
        connected.session().sendMessage(new org.springframework.web.socket.TextMessage(
                "CONNECT\naccept-version:1.2\nhost:localhost\nAuthorization:Bearer " + token + "\n\n" + NULL_TERMINATOR));

        long startedAt = System.nanoTime();
        int code = silent.closeCode().get(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS);
        long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedAt);

        assertThat(code).as("정책 위반(1008)로 닫힌다").isEqualTo(1008);
        assertThat(elapsedSeconds).as("마감은 10초 — 그 전에 닫히면 정상 클라이언트의 CONNECT 를 자른다").isGreaterThanOrEqualTo(8);
        assertThat(connected.session().isOpen()).as("CONNECT 한 소켓은 마감에 걸리지 않는다").isTrue();
        assertThat(meterRegistry.counter("schoolbus.ws.closed", "reason", "CONNECT_TIMEOUT").count())
                .as("마감 종료는 사유별 카운터에 센다(RT-11)").isEqualTo(timedOutBefore + 1);
        connected.session().close();
    }

    private CloseWatcher open() throws Exception {
        CompletableFuture<Integer> closeCode = new CompletableFuture<>();
        WebSocketSession session = new StandardWebSocketClient().execute(new WebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) {
            }

            @Override
            public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {
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
        }, null, URI.create("ws://localhost:" + port + "/ws/location")).get(5, TimeUnit.SECONDS);
        return new CloseWatcher(session, closeCode);
    }

    private record CloseWatcher(WebSocketSession session, CompletableFuture<Integer> closeCode) {
    }
}
