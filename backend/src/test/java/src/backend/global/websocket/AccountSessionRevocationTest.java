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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import io.micrometer.core.instrument.MeterRegistry;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.security.AuthUser;
import src.backend.global.security.JwtTokenProvider;
import src.backend.manager.command.AssignmentCommandService;
import src.backend.manager.dto.AssignmentRequest;
import src.backend.student.command.StudentCommandService;

/**
 * 채널 권한이 바뀌면 그 계정의 열린 WebSocket 세션이 커밋 직후 {@code 4500} 으로 닫히고, 다시 붙어 잃은 채널을 구독하면 {@code 4403} 이다
 * (R57 RT-2 · K2 · Ruling 899 B). 연결 해제 · 퇴원 하나와 기사 교체 하나를 실제 서비스 호출로 커밋해 본다. 서버 스레드가 DB 를 읽으므로 시험 트랜잭션에
 * 묶지 않고 {@link #restore} 가 시드를 되돌린다.
 *
 * <p>시드: 학부모 계정 5 ↔ 학생 1 연결 · 회차 1 의 기사 = 매니저 1(계정 13) · 대체 기사 = 매니저 2(계정 14) · 관계자 = 계정 2(학원 1).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountSessionRevocationTest {

    private static final long GUARDIAN_ACCOUNT = 5L;
    private static final long STUDENT_ID = 1L;
    private static final long DRIVER_ACCOUNT = 13L;
    private static final long RUN_ID = 1L;
    private static final long REPLACEMENT_DRIVER_MANAGER = 2L;
    private static final long ACADEMY_A = 1L;
    private static final long STAFF_ACCOUNT = 2L;
    private static final char NULL_TERMINATOR = '\0';
    private static final long TIMEOUT_SECONDS = 5;

    @LocalServerPort
    private int port;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private StudentCommandService studentCommandService;

    @Autowired
    private AssignmentCommandService assignmentCommandService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @AfterEach
    void restore() {
        jdbcTemplate.update("UPDATE student SET deleted_at = NULL WHERE id = ?", STUDENT_ID);
        jdbcTemplate.update("UPDATE guardian_student SET unlinked_at = NULL WHERE student_id = ?", STUDENT_ID);
        jdbcTemplate.update("UPDATE assignment SET manager_id = 1 WHERE run_id = ? AND role = 'driver'", RUN_ID);
    }

    @Test
    void 퇴원으로_보호자_연결이_끊기면_그_보호자의_열린_세션이_4500_으로_닫히고_다시_구독하면_4403_이다() throws Exception {
        String destination = "/topic/students/" + STUDENT_ID + "/run";
        String token = tokenProvider.createAccessToken(GUARDIAN_ACCOUNT, ACADEMY_A, Role.PARENT, AccountStatus.ACTIVE);
        Client open = connectAndSubscribe(token, destination);
        double revokedBefore = meterRegistry.counter("schoolbus.ws.closed", "reason", "4500").count();

        studentCommandService.withdraw(staff(), STUDENT_ID);

        assertThat(open.closeStatus()).as("연결 해제가 커밋되면 열린 세션이 닫힌다").isEqualTo(4500);
        assertThat(meterRegistry.counter("schoolbus.ws.closed", "reason", "4500").count())
                .as("회수 종료는 사유별 카운터에 센다(RT-11)").isEqualTo(revokedBefore + 1);

        Client again = connectAndSubscribe(token, destination, false);
        assertThat(again.awaitFrame()).as("다시 붙어 잃은 채널을 구독하면 거부된다").startsWith("ERROR").contains("FORBIDDEN");
        assertThat(again.closeStatus()).isEqualTo(4403);
    }

    @Test
    void 기사가_교체되면_빠진_기사의_열린_세션이_4500_으로_닫히고_다시_구독하면_4403_이다() throws Exception {
        String destination = "/topic/manager/runs/" + RUN_ID;
        String token = tokenProvider.createAccessToken(DRIVER_ACCOUNT, ACADEMY_A, Role.DRIVER, AccountStatus.ACTIVE);
        Client open = connectAndSubscribe(token, destination);

        assignmentCommandService.assign(staff(), RUN_ID, new AssignmentRequest(REPLACEMENT_DRIVER_MANAGER, null));

        assertThat(open.closeStatus()).as("기사 교체가 커밋되면 빠진 기사의 세션이 닫힌다").isEqualTo(4500);

        Client again = connectAndSubscribe(token, destination, false);
        assertThat(again.awaitFrame()).startsWith("ERROR").contains("FORBIDDEN");
        assertThat(again.closeStatus()).isEqualTo(4403);
    }

    /** 대조군 — 권한이 바뀌지 않은 다른 계정의 세션은 그대로 열려 있다. */
    @Test
    void 영향받지_않은_계정의_세션은_닫히지_않는다() throws Exception {
        String destination = "/topic/students/3/run";
        String token = tokenProvider.createAccessToken(6L, ACADEMY_A, Role.PARENT, AccountStatus.ACTIVE); // 학생 3 의 보호자
        Client bystander = connectAndSubscribe(token, destination);

        studentCommandService.withdraw(staff(), STUDENT_ID);

        Thread.sleep(500);
        assertThat(bystander.isOpen()).isTrue();
        bystander.close();
    }

    private static AuthUser staff() {
        return new AuthUser(STAFF_ACCOUNT, ACADEMY_A, Role.STAFF, AccountStatus.ACTIVE);
    }

    private Client connectAndSubscribe(String token, String destination) throws Exception {
        return connectAndSubscribe(token, destination, true);
    }

    /** CONNECT 후 SUBSCRIBE. {@code awaitSubscribed} 이면 구독이 성립(방송이 도착)할 때까지 기다린다. */
    private Client connectAndSubscribe(String token, String destination, boolean awaitSubscribed) throws Exception {
        Client client = Client.open(port);
        client.send("CONNECT", "accept-version:1.2", "host:localhost", "Authorization:Bearer " + token);
        assertThat(client.awaitFrame()).startsWith("CONNECTED");
        client.send("SUBSCRIBE", "id:sub-0", "destination:" + destination);
        if (awaitSubscribed) {
            for (int attempt = 0; attempt < 25; attempt++) {
                messagingTemplate.convertAndSend(destination, "subscribed-probe");
                String frame = client.pollFrame(200);
                if (frame != null) {
                    assertThat(frame).startsWith("MESSAGE");
                    return client;
                }
            }
            throw new AssertionError("구독이 성립하지 않았다: " + destination);
        }
        return client;
    }

    /** 프레임과 종료 코드를 모으는 원시 STOMP 클라이언트. */
    private static final class Client implements WebSocketHandler {

        private final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        private final CompletableFuture<Integer> closeCode = new CompletableFuture<>();
        private WebSocketSession session;

        static Client open(int port) throws Exception {
            Client client = new Client();
            client.session = new StandardWebSocketClient()
                    .execute(client, null, URI.create("ws://localhost:" + port + "/ws/location"))
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return client;
        }

        void send(String command, String... headers) throws Exception {
            session.sendMessage(new TextMessage(command + "\n" + String.join("\n", headers) + "\n\n" + NULL_TERMINATOR));
        }

        String awaitFrame() throws InterruptedException {
            String frame = pollFrame(TIMEOUT_SECONDS * 1000);
            assertThat(frame).as("%d초 안에 프레임이 오지 않았다", TIMEOUT_SECONDS).isNotNull();
            return frame;
        }

        String pollFrame(long millis) throws InterruptedException {
            return frames.poll(millis, TimeUnit.MILLISECONDS);
        }

        int closeStatus() throws Exception {
            return closeCode.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        boolean isOpen() {
            return session.isOpen();
        }

        void close() throws Exception {
            session.close();
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
