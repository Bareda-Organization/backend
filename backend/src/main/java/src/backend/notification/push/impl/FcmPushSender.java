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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

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
 * 서명하고 표준 {@link HttpClient} 로 교환한다. 접근 토큰은 만료 1분 전까지 재사용한다.
 *
 * <p>일시 장애(429·5xx·네트워크)는 예외로 알려 아웃박스 재시도에 맡긴다({@link PushSender} 계약).
 * ponytail: 재시도는 행 단위라 여러 단말 중 일부만 실패해도 성공한 단말에 다시 보낸다 — 단말별 발송 기록이 필요해지면
 * 그때 나눈다.
 */
@Component
@ConditionalOnProperty(name = "app.push.sender", havingValue = "fcm")
public class FcmPushSender implements PushSender {

    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static final Duration TOKEN_REFRESH_MARGIN = Duration.ofMinutes(1);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String clientEmail;

    private final PrivateKey privateKey;

    private final URI sendUri;

    private final String tokenUri;

    private final DeviceTokenRepository deviceTokenRepository;

    private final Clock clock;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    private String accessToken;

    private Instant accessTokenExpiresAt = Instant.MIN;

    /** 자격 증명 3개는 기본값이 없다 — prod 에서 빠지면 기동이 실패한다(SSM 주입, Ruling 331). */
    public FcmPushSender(@Value("${app.push.fcm.project-id}") String projectId,
            @Value("${app.push.fcm.client-email}") String clientEmail,
            @Value("${app.push.fcm.private-key}") String privateKeyPem,
            @Value("${app.push.fcm.base-url:https://fcm.googleapis.com}") String baseUrl,
            @Value("${app.push.fcm.token-uri:https://oauth2.googleapis.com/token}") String tokenUri,
            DeviceTokenRepository deviceTokenRepository, Clock clock) {
        this.clientEmail = clientEmail;
        this.privateKey = parsePrivateKey(privateKeyPem);
        this.sendUri = URI.create(baseUrl + "/v1/projects/" + projectId + "/messages:send");
        this.tokenUri = tokenUri;
        this.deviceTokenRepository = deviceTokenRepository;
        this.clock = clock;
    }

    /** 유효한 전 단말에 보낸다 — 단말이 없으면 보낼 곳이 없어 아무것도 하지 않는다(인앱 목록에는 행이 남는다). */
    @Override
    public void send(PushMessage message) {
        List<DeviceToken> tokens = deviceTokenRepository.findAllByAccountIdAndRevokedAtIsNull(
                message.recipientAccountId());
        List<String> failures = new ArrayList<>();
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
            failures.add("status=" + response.statusCode());
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException("FCM 발송 실패 " + failures);
        }
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

    /** 만료 1분 전까지 재사용하고, 그 뒤에는 서비스 계정 JWT 로 새로 받는다. */
    private synchronized String accessToken() {
        Instant now = clock.instant();
        if (accessToken != null && now.isBefore(accessTokenExpiresAt.minus(TOKEN_REFRESH_MARGIN))) {
            return accessToken;
        }
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
        accessToken = node.get("access_token").asString();
        accessTokenExpiresAt = now.plusSeconds(node.get("expires_in").asLong());
        return accessToken;
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
