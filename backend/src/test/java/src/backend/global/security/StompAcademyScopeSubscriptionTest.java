package src.backend.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import src.backend.global.common.SeedFixtures;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;

/**
 * 관제 브로드캐스트 토픽 구독의 학원 격리를 <b>클라이언트가 실제로 받는 STOMP 프레임</b>으로 고정한다.
 *
 * <p>Spring 의 STOMP 클라이언트가 아니라 원시 WebSocket 으로 프레임을 직접 주고받는다 — 검증 대상이
 * "거부되는가" 가 아니라 <b>"거부가 무엇을 실어 오는가"</b> 이기 때문이다. 클라이언트 라이브러리를 끼우면
 * 그 라이브러리가 프레임을 해석한 결과만 보게 되어, 프레임에 식별자가 없어도 테스트가 통과한다.
 *
 * <p>{@code /topic/tenant/...} 는 옛 어휘지만 클라이언트 계약이라 이번 범위 밖이다(Phase 10 등재).
 * 여기서 고정하는 것은 경로가 아니라 그 경로에 적용되는 <b>학원 대조</b>다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StompAcademyScopeSubscriptionTest {

    private static final Long ACADEMY_A = Long.valueOf(SeedFixtures.ACADEMY_A_ID);
    private static final Long ACADEMY_B = Long.valueOf(SeedFixtures.ACADEMY_B_ID);

    private static final long FRAME_TIMEOUT_SECONDS = 5;

    /**
     * 구독 성립을 확인할 브로드캐스트 표식.
     *
     * <p>"ERROR 가 안 왔다" 로 허용을 판정하지 않는 이유는, 구독이 조용히 무시돼도 그 단언이 통과하기
     * 때문이다. 그 토픽으로 실제 메시지를 보내 받아 봐야 구독이 살아 있음이 갈린다.
     */
    private static final String BROADCAST_PROBE = "academy-scope-probe";

    private static final int BROADCAST_ATTEMPTS = 25;
    private static final long BROADCAST_INTERVAL_MS = 200;
    /** STOMP 프레임 종결자는 NULL 옥텟이다 — 다른 문자로 두면 서버가 프레임 끝을 찾지 못한다. */
    private static final char NULL_TERMINATOR = '\0';

    @LocalServerPort
    private int port;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Test
    void A학원_staff_가_B학원_관제_토픽을_구독하면_ERROR_프레임에_ACADEMY_SCOPE_VIOLATION_이_실린다() throws Exception {
        String frame = subscribeAndReadFrame(staffOf(ACADEMY_A), topicOf(ACADEMY_B));

        assertThat(frame).as("거부는 ERROR 프레임으로 온다").startsWith("ERROR");
        assertThat(frame)
                .as("프레임에 격리 위반을 식별할 값이 없으면 클라이언트가 다른 거부와 구별하지 못한다")
                .contains("ACADEMY_SCOPE_VIOLATION");
    }

    /** 이 단언이 고치는 결함 본체다 — 메인 관리자는 전 학원 관제 범위다(ARCHITECTURE §6.2). */
    @Test
    void system_admin_은_타_학원_관제_토픽을_구독한다() throws Exception {
        String frame = subscribeAndReadFrame(systemAdmin(), topicOf(ACADEMY_B));

        assertThat(frame).as("메인 관리자가 거부되면 전 학원 관제가 성립하지 않는다").doesNotStartWith("ERROR");
        assertThat(frame).as("구독이 실제로 성립해야 그 토픽의 브로드캐스트가 배달된다")
                .startsWith("MESSAGE").contains(BROADCAST_PROBE);
    }

    @Test
    void A학원_staff_는_자기_학원_관제_토픽을_구독한다() throws Exception {
        String frame = subscribeAndReadFrame(staffOf(ACADEMY_A), topicOf(ACADEMY_A));

        assertThat(frame).doesNotStartWith("ERROR");
        assertThat(frame).startsWith("MESSAGE").contains(BROADCAST_PROBE);
    }

    // ── STOMP 프레임 왕복 ──────────────────────────────────────────────────

    /** CONNECT → SUBSCRIBE 를 보내고 서버가 SUBSCRIBE 에 대해 돌려주는 첫 프레임을 그대로 반환한다. */
    private String subscribeAndReadFrame(String token, String destination) throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        WebSocketSession session = new StandardWebSocketClient()
                .execute(new FrameCollector(received), null,
                        URI.create("ws://localhost:" + port + "/ws/location"))
                .get(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        try {
            session.sendMessage(new TextMessage(frame("CONNECT",
                    "accept-version:1.2", "host:localhost", "Authorization:Bearer " + token)));
            String connected = take(received);
            assertThat(connected).as("CONNECT 가 먼저 성립해야 구독 인가를 관측할 수 있다").startsWith("CONNECTED");

            session.sendMessage(new TextMessage(frame("SUBSCRIBE",
                    "id:sub-0", "destination:" + destination)));
            return awaitSubscribeOutcome(received, destination);
        } finally {
            session.close();
        }
    }

    private static String take(BlockingQueue<String> received) throws InterruptedException {
        String frame = received.poll(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(frame).as("서버가 %d초 안에 프레임을 돌려주지 않았다", FRAME_TIMEOUT_SECONDS).isNotNull();
        return frame;
    }

    /**
     * 구독 결과를 나타내는 첫 프레임을 기다린다 — 거부면 ERROR 가 즉시 오고, 허용이면 브로드캐스트가
     * 배달된다. 구독 등록과 발행 사이에 경합이 있어 표식을 반복 발행한다.
     */
    private String awaitSubscribeOutcome(BlockingQueue<String> received, String destination)
            throws InterruptedException {

        for (int attempt = 0; attempt < BROADCAST_ATTEMPTS; attempt++) {
            messagingTemplate.convertAndSend(destination, BROADCAST_PROBE);
            String frame = received.poll(BROADCAST_INTERVAL_MS, TimeUnit.MILLISECONDS);
            if (frame != null) {
                return frame;
            }
        }
        throw new AssertionError("구독 결과 프레임(ERROR 또는 MESSAGE)이 오지 않았다: " + destination);
    }

    private static String frame(String command, String... headers) {
        return command + "\n" + String.join("\n", headers) + "\n\n" + NULL_TERMINATOR;
    }

    private static String topicOf(Long academyId) {
        return "/topic/tenant/" + academyId + "/positions";
    }

    private String staffOf(Long academyId) {
        return tokenProvider.createAccessToken(1L, academyId, Role.STAFF, AccountStatus.ACTIVE);
    }

    private String systemAdmin() {
        return tokenProvider.createAccessToken(2L, null, Role.SYSTEM_ADMIN, AccountStatus.ACTIVE);
    }

    /** 수신 텍스트 프레임을 해석하지 않고 원문 그대로 큐에 넣는다 — 프레임 내용이 검증 대상이라서다. */
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
