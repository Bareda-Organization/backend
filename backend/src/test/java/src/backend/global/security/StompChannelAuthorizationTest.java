package src.backend.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Clock;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;

/**
 * {@link StompAuthChannelInterceptor} 의 계정 상태 게이트(목표 8)와 SUBSCRIBE 기본 차단(목표 7)을
 * {@link StompAcademyScopeSubscriptionTest} 와 별도 파일로 둔다 — 그쪽은 학원 격리 한 갈래만 다루고,
 * 이 파일은 4채널 공통 게이트(계정 상태 · 정의되지 않은 목적지 · 배치 여부)를 다뤄 파일명이 검사
 * 대상을 그대로 말하게 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StompChannelAuthorizationTest {

    private static final long FRAME_TIMEOUT_SECONDS = 5;
    private static final char NULL_TERMINATOR = '\0';
    private static final String PROBE = "channel-authz-probe";

    @LocalServerPort
    private int port;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Value("${jwt.secret}")
    private String jwtSecret;

    /**
     * 목표 8(Ruling 87 이월) — REST 의 pending 허용 목록에 WebSocket 대응 항목이 없어 CONNECT 자체를
     * 전면 거부한다(근거는 {@link StompAuthChannelInterceptor#assertActiveAccount} 의 판단 근거 참고).
     * CONNECTED 프레임이 오지 않고 ERROR 로 AUTH_PENDING 이 실리는 것까지 확인해야, "그냥 응답이 없다"
     * 와 "명시적으로 거부한다"가 갈린다.
     */
    @Test
    void PENDING_계정은_CONNECT_에서_AUTH_PENDING_으로_거부된다() throws Exception {
        String token = tokenProvider.createAccessToken(1L, 1L, Role.STAFF, AccountStatus.PENDING);

        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        WebSocketSession session = new StandardWebSocketClient()
                .execute(new FrameCollector(received), null,
                        URI.create("ws://localhost:" + port + "/ws/location"))
                .get(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        try {
            session.sendMessage(new TextMessage(frame("CONNECT",
                    "accept-version:1.2", "host:localhost", "Authorization:Bearer " + token)));
            String outcome = take(received);

            assertThat(outcome).as("CONNECT 자체가 거부돼야 한다 — CONNECTED 가 오면 안 된다")
                    .startsWith("ERROR");
            assertThat(outcome).contains("AUTH_PENDING");
        } finally {
            session.close();
        }
    }

    /**
     * BR-112 — CONNECT 거부도 REST 와 같은 어휘로 알린다(API_SPEC §8.1). 만료는 "재발급 후 재연결",
     * 부재·위조는 "로그인부터" 로 클라이언트의 다음 동작이 갈린다.
     */
    @Test
    void 토큰_없는_CONNECT_는_UNAUTHORIZED_로_거부된다() throws Exception {
        assertThat(connectOutcome("CONNECT", null)).startsWith("ERROR").contains("message:UNAUTHORIZED");
    }

    /** CONNECT 의 별칭인 STOMP 명령으로 연결해도 같은 인증을 거친다 — 명령 이름만 보고 분기하면 인증이 빠진다. */
    @Test
    void STOMP_명령으로_연결해도_토큰이_없으면_UNAUTHORIZED_로_거부된다() throws Exception {
        assertThat(connectOutcome("STOMP", null)).startsWith("ERROR").contains("message:UNAUTHORIZED");
    }

    @Test
    void 만료된_access_토큰의_CONNECT_는_TOKEN_EXPIRED_로_거부된다() throws Exception {
        String expired = new JwtTokenProvider(jwtSecret, -60, 60, Clock.systemUTC())
                .createAccessToken(1L, 1L, Role.STAFF, AccountStatus.ACTIVE);

        assertThat(connectOutcome("CONNECT", expired)).startsWith("ERROR").contains("message:TOKEN_EXPIRED");
    }

    @Test
    void 다른_키로_서명한_토큰의_CONNECT_는_UNAUTHORIZED_로_거부된다() throws Exception {
        String forged = new JwtTokenProvider("forged-secret-forged-secret-forged-secret-0123", 900, 60, Clock.systemUTC())
                .createAccessToken(1L, 1L, Role.STAFF, AccountStatus.ACTIVE);

        assertThat(connectOutcome("CONNECT", forged)).startsWith("ERROR").contains("message:UNAUTHORIZED");
    }

    @Test
    void refresh_토큰의_CONNECT_는_UNAUTHORIZED_로_거부된다() throws Exception {
        String refresh = tokenProvider.createRefreshToken(1L, 1L, Role.STAFF, AccountStatus.ACTIVE);

        assertThat(connectOutcome("CONNECT", refresh)).startsWith("ERROR").contains("message:UNAUTHORIZED");
    }

    /** 연결 프레임 하나를 보내고 서버의 첫 응답 프레임을 돌려준다. {@code token} 이 null 이면 헤더를 뺀다. */
    private String connectOutcome(String command, String token) throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        WebSocketSession session = open(received);
        try {
            String frame = token == null
                    ? frame(command, "accept-version:1.2", "host:localhost")
                    : frame(command, "accept-version:1.2", "host:localhost", "Authorization:Bearer " + token);
            session.sendMessage(new TextMessage(frame));
            return take(received);
        } finally {
            session.close();
        }
    }

    /**
     * 목표 7 — 정의된 4종 목적지 밖은 기본 차단이다(ARCHITECTURE §5.2). 4종 패턴 중 어느 것에도
     * 안 걸리는 임의 목적지를 구독해도 조용히 무시되지 않고 명시적으로 거부돼야 한다.
     */
    @Test
    void 정의되지_않은_목적지_구독은_기본_차단된다() throws Exception {
        String token = tokenProvider.createAccessToken(1L, 1L, Role.STAFF, AccountStatus.ACTIVE);

        String frame = connectAndSubscribe(token, "/topic/unknown/whatever");

        assertThat(frame).as("4종 목적지 밖은 기본 차단이어야 한다 — 화이트리스트 누락이 곧 개방이 되면 안 된다")
                .startsWith("ERROR");
        assertThat(frame).contains("FORBIDDEN");
    }

    /**
     * 목표 7 — 매니저 채널({@code /topic/manager/runs/{runId}})은 그 회차에 배치된 기사·동승자만
     * 구독한다. 역할은 DRIVER 로 맞지만 어떤 회차에도 배치되지 않은(=manager 행이 없는) 계정이면
     * {@link src.backend.run.access.RunAssignmentAccess} 가 FORBIDDEN 으로 막는다.
     */
    @Test
    void 배치되지_않은_기사는_매니저_채널을_구독하지_못한다() throws Exception {
        // 실재하지 않을 만큼 큰 accountId — manager 테이블에 대응 행이 없어 "배치 없음" 을 보장한다.
        String token = tokenProvider.createAccessToken(999_999L, 1L, Role.DRIVER, AccountStatus.ACTIVE);

        String frame = connectAndSubscribe(token, "/topic/manager/runs/1");

        assertThat(frame).as("배치 여부를 확인하지 않으면 남의 회차 관제가 새는 통로가 된다").startsWith("ERROR")
                .contains("message:FORBIDDEN");
    }

    /**
     * 목표 7 — 관리자 채널({@code /topic/admin/live})은 플랫폼 범위(SYSTEM_ADMIN)만 구독한다.
     * STAFF 는 자기 학원 관제 채널까지는 되지만 전 학원 채널은 넘볼 수 없다.
     */
    @Test
    void 학원_STAFF_는_관리자_채널을_구독하지_못한다() throws Exception {
        String token = tokenProvider.createAccessToken(1L, 1L, Role.STAFF, AccountStatus.ACTIVE);

        String frame = connectAndSubscribe(token, "/topic/admin/live");

        assertThat(frame).as("플랫폼 범위가 아니면 관리자 채널은 거부돼야 한다").startsWith("ERROR")
                .contains("message:FORBIDDEN");
    }

    /**
     * BR-008 — 브로커 목적지({@code /topic/**}·{@code /queue/**}·{@code /user/**})로 온 클라이언트 SEND 는
     * 심플 브로커가 구독자에게 그대로 배달한다. 서버 발행분과 구별할 수단이 없으므로 SEND 자체를 거부해야
     * 한다. 구독자 쪽에 가짜 본문이 <b>도착하지 않는다</b>는 것까지 봐야 "거부 프레임만 오고 배달은 됐다" 가 갈린다.
     */
    @Test
    void 브로커_목적지로_SEND_하면_FORBIDDEN_으로_거부되고_구독자에게_배달되지_않는다() throws Exception {
        String destination = "/topic/academy/1/live";
        String fake = "fake-emergency-injected";
        BlockingQueue<String> subscriberFrames = new LinkedBlockingQueue<>();
        WebSocketSession subscriber = open(subscriberFrames);
        BlockingQueue<String> senderFrames = new LinkedBlockingQueue<>();
        WebSocketSession sender = open(senderFrames);
        try {
            connect(subscriber, subscriberFrames, tokenProvider.createAccessToken(1L, 1L, Role.STAFF, AccountStatus.ACTIVE));
            subscriber.sendMessage(new TextMessage(frame("SUBSCRIBE", "id:sub-0", "destination:" + destination)));
            awaitSubscribed(subscriberFrames, destination);

            // 다른 학원의 학부모 — 구독 권한이 전혀 없는 채널로 보낸다
            connect(sender, senderFrames, tokenProvider.createAccessToken(3L, 2L, Role.PARENT, AccountStatus.ACTIVE));
            sender.sendMessage(new TextMessage("SEND\ndestination:" + destination
                    + "\ncontent-type:text/plain\n\n" + fake + NULL_TERMINATOR));

            String leaked = null;
            for (String f; (f = subscriberFrames.poll(2, TimeUnit.SECONDS)) != null; ) {
                if (f.contains(fake)) {
                    leaked = f;
                }
            }
            assertThat(leaked).as("클라이언트 SEND 가 구독자에게 배달되면 가짜 이벤트 주입이 성립한다").isNull();
            String outcome = take(senderFrames);
            assertThat(outcome).startsWith("ERROR").contains("FORBIDDEN");
        } finally {
            subscriber.close();
            sender.close();
        }
    }

    private WebSocketSession open(BlockingQueue<String> received) throws Exception {
        return new StandardWebSocketClient()
                .execute(new FrameCollector(received), null, URI.create("ws://localhost:" + port + "/ws/location"))
                .get(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static void connect(WebSocketSession session, BlockingQueue<String> received, String token)
            throws Exception {
        session.sendMessage(new TextMessage(frame("CONNECT",
                "accept-version:1.2", "host:localhost", "Authorization:Bearer " + token)));
        assertThat(take(received)).startsWith("CONNECTED");
    }

    /** 구독 등록과 발행 사이 경합이 있어, 표식을 반복 발행해 첫 MESSAGE 가 올 때까지 기다린다. */
    private void awaitSubscribed(BlockingQueue<String> received, String destination) throws InterruptedException {
        for (int attempt = 0; attempt < 25; attempt++) {
            messagingTemplate.convertAndSend(destination, "subscribed-probe");
            String f = received.poll(200, TimeUnit.MILLISECONDS);
            if (f != null) {
                assertThat(f).startsWith("MESSAGE");
                return;
            }
        }
        throw new AssertionError("구독이 성립하지 않았다: " + destination);
    }

    // ── BR-102 — 채널별 허용 쪽과 학생 채널 분기. 거부만 있으면 "항상 거부" 로 망가져도 초록이고,
    // 학생 채널은 판정 줄이 빠져도(아무 학부모가 남의 자녀 위치를 받아도) 초록이었다.
    // 시드(V2): parentA1(계정 5)↔학생 1·2 · parentA2(계정 6)↔학생 3·4 · studentA4(계정 10)=학생 4 ·
    // driverA1(계정 13)·escortA1(계정 17) 은 회차 1 배치 · sysadmin(계정 1)

    @Test
    void 학부모는_연결된_자녀_채널을_구독한다() throws Exception {
        assertThat(subscribeOutcome(token(5L, 1L, Role.PARENT), "/topic/students/1/run"))
                .startsWith("MESSAGE").contains(PROBE);
    }

    @Test
    void 학부모는_연결되지_않은_학생_채널을_구독하지_못한다() throws Exception {
        assertThat(subscribeOutcome(token(5L, 1L, Role.PARENT), "/topic/students/3/run"))
                .startsWith("ERROR").contains("message:FORBIDDEN");
    }

    @Test
    void 학생은_본인_채널을_구독한다() throws Exception {
        assertThat(subscribeOutcome(token(10L, 1L, Role.STUDENT), "/topic/students/4/run"))
                .startsWith("MESSAGE").contains(PROBE);
    }

    /** 퇴원한 학생 본인은 자기 채널도 구독하지 못한다(BR-212) — HTTP 조회가 404 로 막히는 것과 같은 판정이다. */
    @Test
    void 퇴원한_학생은_본인_채널을_구독하지_못한다() throws Exception {
        jdbcTemplate.update("UPDATE student SET deleted_at = now() WHERE id = 4");
        try {
            assertThat(subscribeOutcome(token(10L, 1L, Role.STUDENT), "/topic/students/4/run"))
                    .startsWith("ERROR").contains("message:FORBIDDEN");
        } finally {
            jdbcTemplate.update("UPDATE student SET deleted_at = NULL WHERE id = 4");
        }
    }

    @Test
    void 학생은_다른_학생_채널을_구독하지_못한다() throws Exception {
        assertThat(subscribeOutcome(token(10L, 1L, Role.STUDENT), "/topic/students/1/run"))
                .startsWith("ERROR").contains("message:FORBIDDEN");
    }

    @Test
    void 배치된_기사와_동승자는_매니저_채널을_구독한다() throws Exception {
        assertThat(subscribeOutcome(token(13L, 1L, Role.DRIVER), "/topic/manager/runs/1"))
                .startsWith("MESSAGE").contains(PROBE);
        assertThat(subscribeOutcome(token(17L, 1L, Role.ESCORT), "/topic/manager/runs/1"))
                .startsWith("MESSAGE").contains(PROBE);
    }

    @Test
    void 메인_관리자는_관리자_채널을_구독한다() throws Exception {
        assertThat(subscribeOutcome(token(1L, null, Role.SYSTEM_ADMIN), "/topic/admin/live"))
                .startsWith("MESSAGE").contains(PROBE);
    }

    /**
     * BR-083 — 인증은 CONNECT 에서 한 번뿐이라, 연결을 연 토큰이 만료돼도 세션이 계속 방송을 받았다(퇴사·차단은
     * refresh 무효화로 막히지만 열린 세션은 그대로). 세션은 그것을 연 access 토큰보다 오래 살지 않는다 —
     * 만료 뒤 첫 방송 대신 {@code TOKEN_EXPIRED} ERROR 가 가고 연결이 닫힌다.
     */
    @Test
    void 연결을_연_토큰이_만료되면_방송_대신_TOKEN_EXPIRED_로_닫힌다() throws Exception {
        String shortLived = new JwtTokenProvider(jwtSecret, 2, 60, Clock.systemUTC())
                .createAccessToken(1L, 1L, Role.STAFF, AccountStatus.ACTIVE);
        String destination = "/topic/academy/1/live";
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        WebSocketSession session = open(received);
        try {
            connect(session, received, shortLived);
            session.sendMessage(new TextMessage(frame("SUBSCRIBE", "id:sub-0", "destination:" + destination)));
            awaitSubscribed(received, destination);

            Thread.sleep(3_000);
            received.clear();
            messagingTemplate.convertAndSend(destination, "after-expiry");

            String outcome = take(received);
            assertThat(outcome).as("만료 뒤에도 방송이 배달되면 권한 회수가 세션에 닿지 않는다")
                    .startsWith("ERROR").contains("message:TOKEN_EXPIRED").doesNotContain("after-expiry");
            for (int i = 0; i < 25 && session.isOpen(); i++) {
                Thread.sleep(200);
            }
            assertThat(session.isOpen()).as("만료된 세션은 닫혀야 한다").isFalse();
        } finally {
            session.close();
        }
    }

    private String token(Long accountId, Long academyId, Role role) {
        return tokenProvider.createAccessToken(accountId, academyId, role, AccountStatus.ACTIVE);
    }

    /**
     * CONNECT → SUBSCRIBE 뒤 구독 결과 프레임을 돌려준다 — 거부면 ERROR 가, 허용이면 반복 발행한 표식의
     * MESSAGE 가 온다. "ERROR 가 안 왔다" 로 허용을 판정하면 구독이 조용히 무시돼도 통과하므로 표식을 받는다.
     */
    private String subscribeOutcome(String token, String destination) throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        WebSocketSession session = open(received);
        try {
            connect(session, received, token);
            session.sendMessage(new TextMessage(frame("SUBSCRIBE", "id:sub-0", "destination:" + destination)));
            for (int attempt = 0; attempt < 25; attempt++) {
                messagingTemplate.convertAndSend(destination, PROBE);
                String f = received.poll(200, TimeUnit.MILLISECONDS);
                if (f != null) {
                    return f;
                }
            }
            throw new AssertionError("구독 결과 프레임(ERROR 또는 MESSAGE)이 오지 않았다: " + destination);
        } finally {
            session.close();
        }
    }

    /** CONNECT 성공을 전제로, SUBSCRIBE 에 대해 서버가 돌려주는 첫 프레임(보통 ERROR)을 그대로 반환한다. */
    private String connectAndSubscribe(String token, String destination) throws Exception {
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
            return take(received);
        } finally {
            session.close();
        }
    }

    private static String take(BlockingQueue<String> received) throws InterruptedException {
        String frame = received.poll(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(frame).as("서버가 %d초 안에 프레임을 돌려주지 않았다", FRAME_TIMEOUT_SECONDS).isNotNull();
        return frame;
    }

    private static String frame(String command, String... headers) {
        return command + "\n" + String.join("\n", headers) + "\n\n" + NULL_TERMINATOR;
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
