package src.backend.notification.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

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

    private final AtomicInteger tokenRequests = new AtomicInteger();

    private volatile long tokenDelayMillis;

    @BeforeEach
    void 가짜_FCM_을_띄운다() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/token", exchange -> {
            sleepQuietly(tokenDelayMillis);
            respond(exchange, 200, "{\"access_token\":\"access-" + tokenRequests.incrementAndGet()
                    + "\",\"expires_in\":3600,\"token_type\":\"Bearer\"}");
        });
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

    /**
     * R46 S-2 ④ — 접근 토큰 갱신이 느려도 아직 유효한 토큰으로 발송하는 스레드는 막히지 않는다. 만료 5분 전부터는 한 스레드가
     * 뒤에서 미리 갱신하고, 나머지는 옛 토큰을 그대로 쓴다(락을 쥔 채 교환하면 갱신이 끝날 때까지 전 발송이 줄을 선다).
     */
    @Test
    void 접근_토큰_미리_갱신은_발송을_막지_않는다() throws Exception {
        given(deviceTokenRepository.findAllByAccountIdAndRevokedAtIsNull(7L))
                .willReturn(List.of(token(1L, "live-token")));
        AdjustableClock clock = new AdjustableClock(CLOCK.instant());
        FcmPushSender sender = sender(clock, CircuitBreakerRegistry.ofDefaults());
        sender.send(new PushMessage(7L, NotificationType.DELAY, "지연", "본문", false));
        tokenDelayMillis = 2_500;
        clock.advance(Duration.ofSeconds(59 * 60 + 30)); // 만료(60분)까지 30초 — 갱신 구간(예전 1분 전 · 지금 5분 전부터)

        long startedAt = System.nanoTime();
        sender.send(new PushMessage(7L, NotificationType.DELAY, "지연", "본문", false));
        long tookMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        assertThat(tookMillis).as("느린 토큰 교환을 기다리지 않고 옛 토큰으로 바로 보낸다").isLessThan(1_000);
        await().atMost(Duration.ofSeconds(10)).until(() -> tokenRequests.get() == 2);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            sender.send(new PushMessage(7L, NotificationType.DELAY, "지연", "본문", false));
            assertThat(sendRequests.get(sendRequests.size() - 1)).as("갱신이 끝난 뒤에는 새 토큰").startsWith("Bearer access-2 ");
        });
    }

    /** R46 S-2 ① — 일시 장애가 이어지면 서킷이 열려 FCM 을 더 부르지 않고 즉시 거절한다(행은 {@code pending} 으로 남아 워커가 이어받는다). */
    @Test
    void 일시_장애가_이어지면_서킷이_열려_호출하지_않는다() throws Exception {
        sendStatus = 503;
        given(deviceTokenRepository.findAllByAccountIdAndRevokedAtIsNull(7L))
                .willReturn(List.of(token(1L, "live-token")));
        CircuitBreakerRegistry registry = openableCircuits();
        FcmPushSender sender = sender(CLOCK, registry);

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> sender.send(new PushMessage(7L, NotificationType.DELAY, "지연", "본문", false)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("503");
        }

        assertThatThrownBy(() -> sender.send(new PushMessage(7L, NotificationType.DELAY, "지연", "본문", false)))
                .as("서킷이 열린 뒤에는 FCM 에 가지 않고 거절한다").isInstanceOf(CallNotPermittedException.class);
        assertThat(sendRequests).as("열린 뒤의 호출은 서버에 닿지 않는다").hasSize(5);
    }

    /** 단말·본문 문제의 4xx 는 FCM 이 멀쩡하다는 뜻이라 서킷을 열지 않는다(BR-221) — 본문이 잘못된 알림 하나가 전 발송을 막으면 안 된다. */
    @Test
    void 본문_문제의_4xx_는_서킷을_열지_않는다() throws Exception {
        given(deviceTokenRepository.findAllByAccountIdAndRevokedAtIsNull(7L))
                .willReturn(List.of(token(1L, "bad-payload")));
        CircuitBreakerRegistry registry = openableCircuits();
        FcmPushSender sender = sender(CLOCK, registry);

        for (int i = 0; i < 8; i++) {
            assertThatThrownBy(() -> sender.send(new PushMessage(7L, NotificationType.DELAY, "지연", "본문", false)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("400");
        }

        assertThat(sendRequests).as("8번 모두 서버에 도달했다 — 서킷이 열리지 않았다").hasSize(8);
    }

    /**
     * BR-318 — 발송 대상 단말 조회가 DB 오류(연결 풀 고갈)로 실패한 것은 FCM 장애가 아니다. FCM 에 한 번도 닿지 않은 실패가
     * 서킷의 실패 표본이 되면 멀쩡한 FCM 으로의 전 발송이 거절된다.
     */
    @Test
    void 단말_조회가_DB_오류로_실패해도_서킷을_열지_않는다() throws Exception {
        given(deviceTokenRepository.findAllByAccountIdAndRevokedAtIsNull(7L))
                .willThrow(new DataAccessResourceFailureException("연결 풀 고갈"));
        FcmPushSender sender = sender(CLOCK, openableCircuits());

        for (int i = 0; i < 8; i++) {
            assertThatThrownBy(() -> sender.send(new PushMessage(7L, NotificationType.DELAY, "지연", "본문", false)))
                    .as("%d번째 — DB 오류가 그대로 나오고 서킷 거절이 아니다", i + 1)
                    .isInstanceOf(DataAccessResourceFailureException.class);
        }

        doReturn(List.of(token(1L, "live-token"))).when(deviceTokenRepository)
                .findAllByAccountIdAndRevokedAtIsNull(7L);
        sender.send(new PushMessage(7L, NotificationType.DELAY, "지연", "본문", false));
        assertThat(sendRequests).as("DB 가 회복되면 FCM 으로 바로 나간다").hasSize(1);
    }

    /** BR-318 — 무효 토큰 해지 UPDATE 의 DB 오류도 FCM 장애가 아니다. */
    @Test
    void 해지_UPDATE_가_DB_오류로_실패해도_서킷을_열지_않는다() throws Exception {
        given(deviceTokenRepository.findAllByAccountIdAndRevokedAtIsNull(7L))
                .willReturn(List.of(token(2L, "dead-token")));
        doThrow(new DataAccessResourceFailureException("연결 풀 고갈"))
                .when(deviceTokenRepository).revokeInvalid(eq(2L), any());
        FcmPushSender sender = sender(CLOCK, openableCircuits());

        for (int i = 0; i < 8; i++) {
            assertThatThrownBy(() -> sender.send(new PushMessage(7L, NotificationType.DELAY, "지연", "본문", false)))
                    .as("%d번째 — DB 오류가 그대로 나오고 서킷 거절이 아니다", i + 1)
                    .isInstanceOf(DataAccessResourceFailureException.class);
        }

        assertThat(sendRequests).as("8번 모두 FCM 에 닿았다 — 서킷이 열리지 않았다").hasSize(8);
    }

    /** BR-318 을 고치며 해지를 서킷 밖으로 뺐어도, 다른 단말이 일시 장애여서 예외가 나는 발송에서 이미 확인한 무효 토큰은 해지한다. */
    @Test
    void 다른_단말이_일시_장애여도_이미_확인한_무효_토큰은_해지한다() throws Exception {
        sendStatus = 503;
        given(deviceTokenRepository.findAllByAccountIdAndRevokedAtIsNull(7L))
                .willReturn(List.of(token(2L, "dead-token"), token(1L, "live-token")));

        assertThatThrownBy(() -> sender().send(new PushMessage(7L, NotificationType.DELAY, "지연", "본문", false)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("503");

        verify(deviceTokenRepository).revokeInvalid(eq(2L), any());
    }

    /** R46 S-2 ② — 응답이 100~300ms 가 정상이라 10초는 발송 스레드를 너무 오래 묶는다(8스레드 전부 묶이면 처리율 0.8건/s). */
    @Test
    void 요청_시간_상한은_4초_이하다() {
        assertThat(FcmPushSender.TIMEOUT).isLessThanOrEqualTo(Duration.ofSeconds(4));
    }

    /** 실패 5건(실패율 50%)이면 열리는 서킷 — 운영 {@code fcm} 인스턴스와 같은 모양이다. */
    private static CircuitBreakerRegistry openableCircuits() {
        return CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(10).minimumNumberOfCalls(5).failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(1)).build());
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 시험이 시각을 앞으로 보내는 시계. */
    private static final class AdjustableClock extends Clock {

        private volatile Instant now;

        AdjustableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static String invalidArgument(String field) {
        return "{\"error\":{\"code\":400,\"status\":\"INVALID_ARGUMENT\",\"details\":["
                + "{\"@type\":\"type.googleapis.com/google.rpc.BadRequest\",\"fieldViolations\":[{\"field\":\""
                + field + "\",\"description\":\"invalid\"}]},"
                + "{\"@type\":\"type.googleapis.com/google.firebase.fcm.v1.FcmError\",\"errorCode\":\"INVALID_ARGUMENT\"}]}}";
    }

    private FcmPushSender sender() throws Exception {
        return sender(CLOCK, CircuitBreakerRegistry.ofDefaults());
    }

    private FcmPushSender sender(Clock clock, CircuitBreakerRegistry circuitBreakers) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        String base = "http://localhost:" + server.getAddress().getPort();
        return new FcmPushSender("demo", "push@demo.iam.gserviceaccount.com", pem, base, base + "/token",
                deviceTokenRepository, clock, circuitBreakers);
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
