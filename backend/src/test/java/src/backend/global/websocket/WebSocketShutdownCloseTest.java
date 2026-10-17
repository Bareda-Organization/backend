package src.backend.global.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.LifecycleProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.JwtTokenProvider;

/**
 * R59 S-11 — 인스턴스가 종료 신호를 받으면 열린 WebSocket 세션을 {@code 1012}(서비스 재시작)로 닫고, 진행 중인 요청은 끝까지 처리한다(Ruling 900 H · K5). 클라이언트는
 * {@code 4403} 이외의 닫힘이면 다시 접속하므로 다른 인스턴스로 옮겨 붙는다. 순서가 핵심이다 — 세션을 닫는 일이 진행 중 요청이 끝나기 <b>전에</b> 일어나야
 * 요청을 기다리는 동안 새 메시지를 받는 세션이 남지 않는다.
 *
 * <p>수명주기 빈을 모두 멈추므로 이 시험이 쓴 컨텍스트는 다시 쓰지 않는다. 느린 요청은 시험 전용 경로({@code /ws/r59-slow}, 인증 없이 열려 있는 접두사)가 2초 뒤에 답한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.shutdown=graceful", "spring.lifecycle.timeout-per-shutdown-phase=30s"})
@Import(WebSocketShutdownCloseTest.SlowRouteConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WebSocketShutdownCloseTest {

    private static final char NULL_TERMINATOR = '\0';
    private static final long SLOW_REQUEST_MILLIS = 2_000;

    @LocalServerPort
    private int port;

    @Autowired
    private JwtTokenProvider tokenProvider;

    /** 컨텍스트를 닫을 때와 같은 단계 순서로 모든 수명주기 빈을 멈춘다 — 컨텍스트 자체를 닫지는 않아 시험 틀이 뒤처리를 할 수 있다. */
    @Autowired
    private LifecycleProcessor lifecycleProcessor;

    @Test
    void 종료_신호를_받으면_세션은_1012_로_닫히고_진행_중_요청은_200_으로_끝난다() throws Exception {
        AtomicInteger closedCode = new AtomicInteger(-1);
        AtomicLong closedAtNanos = new AtomicLong();
        CountDownLatch connected = new CountDownLatch(1);
        CountDownLatch closedLatch = new CountDownLatch(1);
        // 닫힘 코드를 있는 그대로 받으려고 JDK 클라이언트를 쓴다 — 톰캣 클라이언트는 1012 를 모르는 코드로 보고 프로토콜 오류(1002)로 바꿔 받는다
        java.net.http.WebSocket client = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/location"), new java.net.http.WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onText(java.net.http.WebSocket webSocket, CharSequence data, boolean last) {
                        if (data.toString().startsWith("CONNECTED")) {
                            connected.countDown();
                        }
                        webSocket.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(java.net.http.WebSocket webSocket, int statusCode, String reason) {
                        closedAtNanos.set(System.nanoTime());
                        closedCode.set(statusCode);
                        closedLatch.countDown();
                        return null;
                    }
                }).get(5, TimeUnit.SECONDS);
        String token = tokenProvider.createAccessToken(2L, 1L, Role.STAFF, AccountStatus.ACTIVE);
        client.sendText("CONNECT\naccept-version:1.2\nhost:localhost\nAuthorization:Bearer " + token + "\n\n" + NULL_TERMINATOR, true);
        assertThat(connected.await(5, TimeUnit.SECONDS)).as("STOMP CONNECTED").isTrue();

        HttpClient http = HttpClient.newHttpClient();
        AtomicLong requestDoneAtNanos = new AtomicLong();
        CompletableFuture<HttpResponse<String>> slow = http
                .sendAsync(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/ws/r59-slow")).build(),
                        HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> requestDoneAtNanos.set(System.nanoTime()));
        Thread.sleep(500); // 요청이 서버에 들어가 처리 중인 상태에서 종료를 시작한다

        Thread shutdown = new Thread(lifecycleProcessor::stop, "r59-shutdown");
        shutdown.start();

        assertThat(closedLatch.await(10, TimeUnit.SECONDS)).as("세션이 닫혔다").isTrue();
        assertThat(closedCode.get()).as("종료 때 세션의 닫힘 코드").isEqualTo(1012);
        HttpResponse<String> response = slow.get(15, TimeUnit.SECONDS);
        assertThat(response.statusCode()).as("종료가 시작될 때 진행 중이던 요청").isEqualTo(200);
        assertThat(closedAtNanos.get()).as("세션이 닫힌 시각이 진행 중 요청이 끝난 시각보다 앞선다")
                .isLessThan(requestDoneAtNanos.get());
        shutdown.join(Duration.ofSeconds(30));
    }

    /** 느린 요청을 흉내 내는 시험 전용 경로. */
    static class SlowRouteConfig {

        @Bean
        RouterFunction<ServerResponse> slowRoute() {
            return RouterFunctions.route().GET("/ws/r59-slow", request -> {
                Thread.sleep(SLOW_REQUEST_MILLIS);
                return ServerResponse.ok().body("done");
            }).build();
        }
    }
}
