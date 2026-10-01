package src.backend.notification.push.impl;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.jsonwebtoken.Jwts;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import src.backend.notification.entity.DeviceToken;
import src.backend.notification.push.spec.PushMessage;
import src.backend.notification.push.spec.PushSender;
import src.backend.notification.repository.DeviceTokenRepository;

/**
 * FCM HTTP v1 한 채널 발송(Ruling 331 — android·ios(Firebase 가 APNs 로 중계)·web). 수신 계정의 유효한 전 단말에
 * 보내고, FCM 이 무효라고 답한 토큰({@code UNREGISTERED}·{@code INVALID_ARGUMENT})은 해지한다(API_SPEC §2.11).
 *
 * <p>인증은 서비스 계정 키로 서명한 JWT 를 접근 토큰으로 바꾸는 OAuth2 흐름이다 — 새 의존성 없이 이미 쓰는 jjwt 로
 * 서명하고 표준 {@link HttpClient} 로 교환한다. 접근 토큰은 만료 5분 전부터 뒤에서 미리 갱신하고 그동안 옛 토큰을 쓴다
 * (R46 S-2 — 교환을 락 안에서 하면 FCM 인증 서버가 느린 동안 전 발송 스레드가 줄을 선다).
 *
 * <p><b>서킷</b>({@code resilience4j} 인스턴스 {@code fcm} — 값은 {@code placeSearch} 와 같다)은 <b>일시 장애</b>(네트워크 ·
 * 429 · 5xx · 접근 토큰 발급 실패)만 센다. 열리면 호출 없이 즉시 거절하고, 행은 {@code pending} 으로 남아 아웃박스 워커가
 * 이어받는다. 본문·단말 문제의 4xx 는 FCM 이 멀쩡하다는 뜻이라 세지 않는다 — 잘못된 알림 하나가 전 발송을 막으면 안 된다.
 *
 * <p>일시 장애(429·5xx·네트워크)는 예외로 알려 아웃박스 재시도에 맡긴다({@link PushSender} 계약).
 * ponytail: 재시도는 행 단위라 여러 단말 중 일부만 실패해도 성공한 단말에 다시 보낸다 — 단말별 발송 기록이 필요해지면
 * 그때 나눈다.
 */
@Component
@ConditionalOnProperty(name = "app.push.sender", havingValue = "fcm")
public class FcmPushSender implements PushSender {

    /** {@code resilience4j.circuitbreaker.instances} 의 이름 — 값은 장소 검색({@code placeSearch})과 같다(R46 S-2). */
    static final String RESILIENCE_INSTANCE = "fcm";

    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

    /**
     * 연결·요청 시간 상한 — 시험이 값을 못박는다({@code FcmPushSenderTest}). 응답이 100~300ms 가 정상이라 4초면 충분하고,
     * 10초면 FCM 이 응답하지 않을 때 발송 스레드 8개가 모두 10초씩 묶여 처리율이 0.8건/s 로 떨어진다(R46 S-2).
     */
    public static final Duration TIMEOUT = Duration.ofSeconds(4);

    /** 접근 토큰 만료 이 시간 전부터 한 스레드가 뒤에서 미리 갱신한다 — 그 사이 나머지는 아직 유효한 옛 토큰을 쓴다. */
    private static final Duration TOKEN_REFRESH_AHEAD = Duration.ofMinutes(5);

    private static final Logger log = LoggerFactory.getLogger(FcmPushSender.class);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String clientEmail;

    private final PrivateKey privateKey;

    private final URI sendUri;

    private final String tokenUri;

    private final DeviceTokenRepository deviceTokenRepository;

    private final Clock clock;

    private final CircuitBreaker circuitBreaker;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    /** 가장 최근에 받은 접근 토큰 — 읽는 쪽은 락 없이 본다. */
    private final AtomicReference<AccessToken> accessToken = new AtomicReference<>();

    /** 미리 갱신이 이미 돌고 있는지 — 같은 구간에 여러 스레드가 동시에 교환하지 않게 한다. */
    private final AtomicBoolean refreshingAhead = new AtomicBoolean();

    private record AccessToken(String value, Instant expiresAt) {
    }

    /** 자격 증명 3개는 기본값이 없다 — prod 에서 빠지면 기동이 실패한다(SSM 주입, Ruling 331). */
    public FcmPushSender(@Value("${app.push.fcm.project-id}") String projectId,
            @Value("${app.push.fcm.client-email}") String clientEmail,
            @Value("${app.push.fcm.private-key}") String privateKeyPem,
            @Value("${app.push.fcm.base-url:https://fcm.googleapis.com}") String baseUrl,
            @Value("${app.push.fcm.token-uri:https://oauth2.googleapis.com/token}") String tokenUri,
            DeviceTokenRepository deviceTokenRepository, Clock clock, CircuitBreakerRegistry circuitBreakers) {
        this.clientEmail = clientEmail;
        this.privateKey = parsePrivateKey(privateKeyPem);
        this.sendUri = URI.create(baseUrl + "/v1/projects/" + projectId + "/messages:send");
        this.tokenUri = tokenUri;
        this.deviceTokenRepository = deviceTokenRepository;
        this.clock = clock;
        this.circuitBreaker = circuitBreakers.circuitBreaker(RESILIENCE_INSTANCE);
    }

    /** 유효한 전 단말에 보낸다 — 단말이 없으면 보낼 곳이 없어 아무것도 하지 않는다(인앱 목록에는 행이 남는다). */
    @Override
    public void send(PushMessage message) {
        List<String> rejections = circuitBreaker.executeSupplier(() -> sendToAll(message));
        if (!rejections.isEmpty()) {
            throw new IllegalStateException("FCM 발송 실패 " + rejections);
        }
    }

    /**
     * 단말마다 보내고 FCM 이 거절한 4xx(본문 문제 등)를 모아 돌려준다. <b>일시 장애는 여기서 던진다</b> — 서킷이 센다.
     * 4xx 는 값으로 돌려줘 서킷 밖에서 실패 처리한다.
     */
    private List<String> sendToAll(PushMessage message) {
        List<DeviceToken> tokens = deviceTokenRepository.findAllByAccountIdAndRevokedAtIsNull(
                message.recipientAccountId());
        List<String> transientFailures = new ArrayList<>();
        List<String> rejections = new ArrayList<>();
        for (DeviceToken token : tokens) {
            HttpResponse<String> response = post(sendUri, "application/json", payloadOf(message, token.getToken()),
                    accessToken());
            if (response.statusCode() == 200) {
                continue;
            }
            if (isInvalidToken(response)) {
                deviceTokenRepository.revokeInvalid(token.getId(), OffsetDateTime.now(clock));
                continue;
            }
            (isTransient(response.statusCode()) ? transientFailures : rejections).add("status=" + response.statusCode());
        }
        if (!transientFailures.isEmpty()) {
            throw new IllegalStateException("FCM 발송 실패 " + transientFailures + rejections);
        }
        return rejections;
    }

    /** 429 · 5xx — FCM 쪽 사정이라 다시 보내면 도착할 수 있다. */
    private static boolean isTransient(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }

    /**
     * 404 {@code UNREGISTERED} · 400 {@code INVALID_ARGUMENT} — 그 토큰으로는 다시 보내도 도착하지 않는다.
     * 단 {@code INVALID_ARGUMENT} 는 본문(제목·본문·{@code data}) 이 거부돼도 같은 코드라서(BR-221), 위반 필드가
     * 밝혀졌고 {@code message.token} 이 아니면 토큰 탓이 아니므로 해지하지 않고 실패로 남겨 재시도에 맡긴다.
     */
    private static boolean isInvalidToken(HttpResponse<String> response) {
        String body = response.body() == null ? "" : response.body();
        if (response.statusCode() == 404) {
            return body.contains("UNREGISTERED");
        }
        return response.statusCode() == 400 && body.contains("INVALID_ARGUMENT") && !blamesOtherThanToken(body);
    }

    /** 응답의 {@code fieldViolations} 가 있고 그 어느 것도 {@code message.token} 이 아니면 본문 문제다. */
    private static boolean blamesOtherThanToken(String body) {
        try {
            boolean anyViolation = false;
            for (JsonNode detail : JSON.readTree(body).path("error").path("details")) {
                for (JsonNode violation : detail.path("fieldViolations")) {
                    anyViolation = true;
                    if (violation.path("field").asString("").startsWith("message.token")) {
                        return false;
                    }
                }
            }
            return anyViolation;
        } catch (RuntimeException e) {
            return false; // 읽을 수 없는 본문은 예전처럼 토큰 오류로 본다
        }
    }

    private static String payloadOf(PushMessage message, String token) {
        Map<String, Object> payload = Map.of("message", Map.of(
                "token", token,
                "notification", Map.of("title", message.title(), "body", message.body()),
                "data", Map.of("type", message.type().name().toLowerCase(java.util.Locale.ROOT),
                        "popup", String.valueOf(message.popup()))));
        return JSON.writeValueAsString(payload);
    }

    /**
     * 유효한 토큰은 락 없이 바로 돌려주고, 만료 {@link #TOKEN_REFRESH_AHEAD} 전부터는 한 스레드가 뒤에서 미리 갱신한다.
     * 이미 만료됐거나 아직 없으면 이 호출이 직접 교환한다 — 그때도 락을 쥐지 않아 다른 스레드가 이 호출을 기다리지 않는다
     * (만료 순간 동시에 오는 발송은 각자 교환한다 — 하루 한 번 안팎이고 스레드가 최대 10개라 받아들인다).
     */
    private String accessToken() {
        Instant now = clock.instant();
        AccessToken current = accessToken.get();
        if (current == null || !now.isBefore(current.expiresAt())) {
            return exchangeAccessToken(now).value();
        }
        if (!now.isBefore(current.expiresAt().minus(TOKEN_REFRESH_AHEAD)) && refreshingAhead.compareAndSet(false, true)) {
            Thread.startVirtualThread(this::refreshAhead);
        }
        return current.value();
    }

    private void refreshAhead() {
        try {
            exchangeAccessToken(clock.instant());
        } catch (RuntimeException e) {
            log.warn("[fcm] 접근 토큰 미리 갱신이 실패해 만료 때 다시 받는다 — {}", e.getMessage());
        } finally {
            refreshingAhead.set(false);
        }
    }

    /** 서비스 계정 JWT 를 접근 토큰으로 바꿔 보관한다 — 일시 장애로 센다(서킷). */
    private AccessToken exchangeAccessToken(Instant now) {
        String assertion = Jwts.builder()
                .issuer(clientEmail)
                .claim("scope", SCOPE)
                .audience().single(tokenUri)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofHours(1))))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
        String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer",
                StandardCharsets.UTF_8) + "&assertion=" + assertion;
        HttpResponse<String> response = post(URI.create(tokenUri), "application/x-www-form-urlencoded", form, null);
        if (response.statusCode() != 200) {
            throw new IllegalStateException("FCM 접근 토큰 발급 실패 status=" + response.statusCode());
        }
        JsonNode node = JSON.readTree(response.body());
        AccessToken exchanged = new AccessToken(node.get("access_token").asString(),
                now.plusSeconds(node.get("expires_in").asLong()));
        accessToken.set(exchanged);
        return exchanged;
    }

    private HttpResponse<String> post(URI uri, String contentType, String body, String bearer) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(TIMEOUT)
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("FCM 호출 실패 — " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("FCM 호출 중단", e);
        }
    }

    /** PEM(PKCS#8) 서비스 계정 키 — 환경변수로 넘기며 줄바꿈이 {@code \n} 문자로 바뀐 경우도 받는다. */
    private static PrivateKey parsePrivateKey(String pem) {
        String base64 = pem.replace("\\n", "\n")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        try {
            return KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("FCM 서비스 계정 키를 읽을 수 없다", e);
        }
    }
}
