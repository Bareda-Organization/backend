package src.backend.notification.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import src.backend.notification.entity.DevicePlatform;
import src.backend.notification.entity.DeviceToken;
import src.backend.notification.entity.NotificationType;
import src.backend.notification.push.impl.FcmPushSender;
import src.backend.notification.push.spec.PushMessage;
import src.backend.notification.repository.DeviceTokenRepository;

/**
 * BR-040 · Ruling 331 — FCM HTTP v1 한 채널로 수신 계정의 유효한 전 단말에 보내고, FCM 이 무효라고 답한 토큰은
 * 해지한다. Firebase 키가 없으므로 토큰 발급·발송 두 끝점을 흉내 내는 가짜 HTTP 서버로 확인한다.
 */
class FcmPushSenderTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2030-04-01T00:00:00Z"), ZoneOffset.UTC);

    private final List<String> sendRequests = new CopyOnWriteArrayList<>();

    private final DeviceTokenRepository deviceTokenRepository = mock(DeviceTokenRepository.class);

    private HttpServer server;

    private int sendStatus = 200;

    @BeforeEach
    void 가짜_FCM_을_띄운다() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/token", exchange -> respond(exchange, 200,
                "{\"access_token\":\"access-1\",\"expires_in\":3600,\"token_type\":\"Bearer\"}"));
        server.createContext("/v1/projects/demo/messages:send", exchange -> {
            String body = read(exchange.getRequestBody());
            sendRequests.add(exchange.getRequestHeaders().getFirst("Authorization") + " " + body);
            if (body.contains("\"dead-token\"")) {
                respond(exchange, 404, "{\"error\":{\"code\":404,\"status\":\"NOT_FOUND\",\"details\":[{\"@type\":"
                        + "\"type.googleapis.com/google.firebase.fcm.v1.FcmError\",\"errorCode\":\"UNREGISTERED\"}]}}");
            } else if (body.contains("\"bad-token\"")) {
                respond(exchange, 400, invalidArgument("message.token"));
            } else if (body.contains("\"bad-payload\"")) {
                respond(exchange, 400, invalidArgument("message.data[0].value"));
            } else {
                respond(exchange, sendStatus, sendStatus == 200 ? "{\"name\":\"projects/demo/messages/1\"}"
                        : "{\"error\":{\"code\":503,\"status\":\"UNAVAILABLE\"}}");
            }
        });
        server.start();
    }

    @AfterEach
    void 서버를_내린다() {
        server.stop(0);
    }

    @Test
    void 유효한_전_단말에_보내고_무효_토큰은_해지한다() throws Exception {
        given(deviceTokenRepository.findAllByAccountIdAndRevokedAtIsNull(7L))
                .willReturn(List.of(token(1L, "live-token"), token(2L, "dead-token")));

        sender().send(new PushMessage(7L, NotificationType.EMERGENCY, "비상", "비상 발생", true));

        assertThat(sendRequests).hasSize(2).allMatch(request -> request.startsWith("Bearer access-1 "));
        assertThat(sendRequests).anyMatch(request -> request.contains("\"live-token\"") && request.contains("비상 발생"));
        verify(deviceTokenRepository).revokeInvalid(eq(2L), any());
        verify(deviceTokenRepository, never()).revokeInvalid(eq(1L), any());
    }

    @Test
    void 일시_장애는_예외로_알려_재시도에_맡긴다() throws Exception {
        sendStatus = 503;
        given(deviceTokenRepository.findAllByAccountIdAndRevokedAtIsNull(7L))
                .willReturn(List.of(token(1L, "live-token")));

        assertThatThrownBy(() -> sender().send(new PushMessage(7L, NotificationType.DELAY, "지연", "본문", false)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("503");
        verify(deviceTokenRepository, never()).revokeInvalid(any(), any());
    }

    /** BR-221 — 본문(payload) 때문에 생긴 {@code INVALID_ARGUMENT} 는 토큰 탓이 아니라 단말을 해지하지 않고 재시도에 맡긴다. */
    @Test
    void 본문_문제의_INVALID_ARGUMENT_는_단말을_해지하지_않는다() throws Exception {
        given(deviceTokenRepository.findAllByAccountIdAndRevokedAtIsNull(7L))
                .willReturn(List.of(token(1L, "bad-payload")));

        assertThatThrownBy(() -> sender().send(new PushMessage(7L, NotificationType.DELAY, "지연", "본문", false)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("400");
        verify(deviceTokenRepository, never()).revokeInvalid(any(), any());
    }

    /** BR-221 — {@code message.token} 을 가리키는 {@code INVALID_ARGUMENT} 는 여전히 무효 토큰이라 해지한다. */
    @Test
    void 토큰_문제의_INVALID_ARGUMENT_는_해지한다() throws Exception {
        given(deviceTokenRepository.findAllByAccountIdAndRevokedAtIsNull(7L))
                .willReturn(List.of(token(3L, "bad-token")));

        sender().send(new PushMessage(7L, NotificationType.DELAY, "지연", "본문", false));

        verify(deviceTokenRepository).revokeInvalid(eq(3L), any());
    }

    private static String invalidArgument(String field) {
        return "{\"error\":{\"code\":400,\"status\":\"INVALID_ARGUMENT\",\"details\":["
                + "{\"@type\":\"type.googleapis.com/google.rpc.BadRequest\",\"fieldViolations\":[{\"field\":\""
                + field + "\",\"description\":\"invalid\"}]},"
                + "{\"@type\":\"type.googleapis.com/google.firebase.fcm.v1.FcmError\",\"errorCode\":\"INVALID_ARGUMENT\"}]}}";
    }

    private FcmPushSender sender() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        String base = "http://localhost:" + server.getAddress().getPort();
        return new FcmPushSender("demo", "push@demo.iam.gserviceaccount.com", pem, base, base + "/token",
                deviceTokenRepository, CLOCK);
    }

    private static DeviceToken token(long id, String value) {
        DeviceToken token = DeviceToken.register(7L, "device-" + id, value, DevicePlatform.values()[0], null);
        ReflectionTestUtils.setField(token, "id", id);
        return token;
    }

    private static String read(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
