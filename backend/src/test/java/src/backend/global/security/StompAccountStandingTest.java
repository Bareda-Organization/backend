package src.backend.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;

import testsupport.clock.AdvanceableClock;
import testsupport.clock.AdvanceableClockConfig;

/**
 * 권한을 잃은 계정은 WebSocket 에서도 새 연결 · 구독 · 송신을 거절당하고, 이미 열린 세션은 끊긴다 — Ruling 880.
 *
 * <p>상태를 바꾸기 전 같은 토큰이 통과하는 것(정상 계정)을 먼저 확인한다. 서버 스레드가 DB 를 읽으므로 시험 트랜잭션에 묶지 않고
 * 직접 커밋하며, {@link #restore} 가 관계자 A(계정 2)의 재직 상태를 되돌린다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AdvanceableClockConfig.class)
class StompAccountStandingTest {

    private static final long STAFF_A_ACCOUNT = 2L;
    private static final long ACADEMY_A = 1L;
    private static final String DESTINATION = "/topic/academy/1/live";
    private static final char NULL_TERMINATOR = '\0';
    private static final long FRAME_TIMEOUT_SECONDS = 5;

    @LocalServerPort
    private int port;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AdvanceableClock advanceableClock;

    @Autowired
    private StompStandingSweepScheduler sweepScheduler;

    @AfterEach
    void restore() {
        jdbcTemplate.update("UPDATE academy_staff SET status = 'active' WHERE account_id = ?", STAFF_A_ACCOUNT);
        jdbcTemplate.update("UPDATE account SET status = 'active', status_before_block = NULL, blocked_at = NULL, "
                + "block_reason = NULL WHERE id = ?", STAFF_A_ACCOUNT);
        advanceableClock.reset();
    }

    @Test
    void 퇴사한_관계자의_새_연결은_TOKEN_EXPIRED_로_거부되고_재직_중이면_연결된다() throws Exception {
        assertThat(connectOutcome()).as("재직 중에는 연결된다").startsWith("CONNECTED");

        retire();

        assertThat(connectOutcome()).startsWith("ERROR").contains("message:TOKEN_EXPIRED");
    }

    /** 퇴사 외의 갈래 — 차단(blocked)도 같은 규칙이다. 차단은 계정 상태 컬럼이라 퇴사(관계자 재직 표)와 판정 경로가 다르다. */
    @Test
    void 차단된_계정의_새_연결은_TOKEN_EXPIRED_로_거부된다() throws Exception {
        assertThat(connectOutcome()).as("차단 전에는 연결된다").startsWith("CONNECTED");

        jdbcTemplate.update("UPDATE account SET status = 'blocked', status_before_block = 'active', "
                + "blocked_at = now(), block_reason = '시험' WHERE id = ?", STAFF_A_ACCOUNT);

        assertThat(connectOutcome()).startsWith("ERROR").contains("message:TOKEN_EXPIRED");
    }

    @Test
    void 열린_세션도_퇴사하면_새_구독이_TOKEN_EXPIRED_로_거부된다() throws Exception {
        BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        WebSocketSession session = open(frames);
        try {
            connect(session, frames);
            session.sendMessage(new TextMessage(frame("SUBSCRIBE", "id:sub-0", "destination:" + DESTINATION)));
            awaitSubscribed(frames); // 재직 중에는 구독이 통과한다

            retire();
            session.sendMessage(new TextMessage(frame("SUBSCRIBE", "id:sub-1", "destination:" + DESTINATION)));

            assertThat(take(frames)).as("퇴사 뒤 새 구독").startsWith("ERROR").contains("message:TOKEN_EXPIRED");
        } finally {
            session.close();
        }
    }

    @Test
    void 열린_세션도_퇴사하면_새_송신이_TOKEN_EXPIRED_로_거부된다() throws Exception {
        BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        WebSocketSession session = open(frames);
        try {
            connect(session, frames);

            retire();
            session.sendMessage(new TextMessage("SEND\ndestination:/app/standing-probe\ncontent-type:text/plain\n\nx"
                    + NULL_TERMINATOR));

            assertThat(take(frames)).as("퇴사 뒤 새 송신").startsWith("ERROR").contains("message:TOKEN_EXPIRED");
        } finally {
            session.close();
        }
    }

    /** 이미 구독을 마친 세션은 요청이 없어도 점검 스케줄러가 한 번 돈 뒤 첫 방송이 ERROR 로 바뀌며 닫힌다. */
    @Test
    void 이미_열린_세션은_퇴사_뒤_점검이_돌면_방송_대신_TOKEN_EXPIRED_로_닫힌다() throws Exception {
        BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        WebSocketSession session = open(frames);
        try {
            connect(session, frames);
            session.sendMessage(new TextMessage(frame("SUBSCRIBE", "id:sub-0", "destination:" + DESTINATION)));
            awaitSubscribed(frames);

            retire();
            sweepScheduler.sweep(); // 30초마다 도는 스케줄러를 직접 한 번 돌린다(시험 JVM 에서는 하루 뒤로 미뤄져 있다)
            messagingTemplate.convertAndSend(DESTINATION, "after-retire");

            assertThat(takeAfterProbes(frames)).as("퇴사한 계정의 세션에 방송이 배달되면 권한 회수가 열린 세션에 닿지 않는다")
                    .startsWith("ERROR").contains("message:TOKEN_EXPIRED").doesNotContain("after-retire");
            for (int i = 0; i < 25 && session.isOpen(); i++) {
                Thread.sleep(200);
            }
            assertThat(session.isOpen()).as("세션이 닫혀야 한다").isFalse();
        } finally {
            session.close();
        }
    }

    private void retire() {
        jdbcTemplate.update("UPDATE academy_staff SET status = 'inactive' WHERE account_id = ?", STAFF_A_ACCOUNT);
    }

    private String token() {
        return tokenProvider.createAccessToken(STAFF_A_ACCOUNT, ACADEMY_A, Role.STAFF, AccountStatus.ACTIVE);
    }

    private String connectOutcome() throws Exception {
        BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        WebSocketSession session = open(frames);
        try {
            session.sendMessage(new TextMessage(frame("CONNECT", "accept-version:1.2", "host:localhost",
                    "Authorization:Bearer " + token())));
            return take(frames);
        } finally {
            session.close();
        }
    }

    private void connect(WebSocketSession session, BlockingQueue<String> frames) throws Exception {
        session.sendMessage(new TextMessage(frame("CONNECT", "accept-version:1.2", "host:localhost",
                "Authorization:Bearer " + token())));
        assertThat(take(frames)).startsWith("CONNECTED");
    }

    private WebSocketSession open(BlockingQueue<String> frames) throws Exception {
        return new StandardWebSocketClient()
                .execute(new Collector(frames), null, URI.create("ws://localhost:" + port + "/ws/location"))
                .get(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /** 구독 등록과 발행 사이 경합이 있어 표식을 반복 발행해 첫 MESSAGE 가 올 때까지 기다린다. */
    private void awaitSubscribed(BlockingQueue<String> frames) throws InterruptedException {
        for (int attempt = 0; attempt < 25; attempt++) {
            messagingTemplate.convertAndSend(DESTINATION, "subscribed-probe");
            String frame = frames.poll(200, TimeUnit.MILLISECONDS);
            if (frame != null) {
                assertThat(frame).startsWith("MESSAGE");
                return;
            }
        }
        throw new AssertionError("구독이 성립하지 않았다: " + DESTINATION);
    }

    /** 구독 확인 표식 MESSAGE 를 건너뛰고 그 뒤 첫 프레임을 돌려준다 — 표식은 여러 개 날아가 있을 수 있고, 시계를 민 뒤 도착한 표식은 그 자체로 ERROR 가 된다. */
    private static String takeAfterProbes(BlockingQueue<String> frames) throws InterruptedException {
        String frame = take(frames);
        while (frame.startsWith("MESSAGE") && frame.contains("subscribed-probe")) {
            frame = take(frames);
        }
        return frame;
    }

    private static String take(BlockingQueue<String> frames) throws InterruptedException {
        String frame = frames.poll(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(frame).as("서버가 %d초 안에 프레임을 돌려주지 않았다", FRAME_TIMEOUT_SECONDS).isNotNull();
        return frame;
    }

    private static String frame(String command, String... headers) {
        return command + "\n" + String.join("\n", headers) + "\n\n" + NULL_TERMINATOR;
    }

    private record Collector(BlockingQueue<String> frames) implements WebSocketHandler {

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
        }

        @Override
        public boolean supportsPartialMessages() {
            return false;
        }
    }
}
