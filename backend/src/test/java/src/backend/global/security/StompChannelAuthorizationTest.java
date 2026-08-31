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

    @LocalServerPort
    private int port;

    @Autowired
    private JwtTokenProvider tokenProvider;

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

        assertThat(frame).as("배치 여부를 확인하지 않으면 남의 회차 관제가 새는 통로가 된다").startsWith("ERROR");
    }

    /**
     * 목표 7 — 관리자 채널({@code /topic/admin/live})은 플랫폼 범위(SYSTEM_ADMIN)만 구독한다.
     * STAFF 는 자기 학원 관제 채널까지는 되지만 전 학원 채널은 넘볼 수 없다.
     */
    @Test
    void 학원_STAFF_는_관리자_채널을_구독하지_못한다() throws Exception {
        String token = tokenProvider.createAccessToken(1L, 1L, Role.STAFF, AccountStatus.ACTIVE);

        String frame = connectAndSubscribe(token, "/topic/admin/live");

        assertThat(frame).as("플랫폼 범위가 아니면 관리자 채널은 거부돼야 한다").startsWith("ERROR");
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
