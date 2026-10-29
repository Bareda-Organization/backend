package src.backend.notification.push.impl;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.jsonwebtoken.Jwts;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * FCM 접근 토큰 — 서비스 계정 키로 서명한 JWT 를 OAuth2 로 접근 토큰으로 바꾸고 보관한다. 만료 5분 전부터 뒤에서 미리 갱신하고 그동안 옛 토큰을 쓴다
 * (교환을 락 안에서 하면 FCM 인증 서버가 느린 동안 전 발송 스레드가 줄을 선다). {@link FcmPushSender} 가 하나 들고 쓰는 평범한 객체다.
 */
final class FcmAccessTokens {

    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

    /** 접근 토큰 만료 이 시간 전부터 한 스레드가 뒤에서 미리 갱신한다 — 그 사이 나머지는 아직 유효한 옛 토큰을 쓴다. */
    private static final Duration TOKEN_REFRESH_AHEAD = Duration.ofMinutes(5);

    private static final Logger log = LoggerFactory.getLogger(FcmAccessTokens.class);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String clientEmail;

    private final PrivateKey privateKey;

    private final String tokenUri;

    private final Clock clock;

    private final FcmHttp http;

    /** 가장 최근에 받은 접근 토큰 — 읽는 쪽은 락 없이 본다. */
    private final AtomicReference<AccessToken> cached = new AtomicReference<>();

    /** 미리 갱신이 이미 돌고 있는지 — 같은 구간에 여러 스레드가 동시에 교환하지 않게 한다. */
    private final AtomicBoolean refreshingAhead = new AtomicBoolean();

    private record AccessToken(String value, Instant expiresAt) {
    }

    FcmAccessTokens(String clientEmail, String privateKeyPem, String tokenUri, Clock clock, FcmHttp http) {
        this.clientEmail = clientEmail;
        this.privateKey = parsePrivateKey(privateKeyPem);
        this.tokenUri = tokenUri;
        this.clock = clock;
        this.http = http;
    }

    /**
     * 유효한 토큰은 락 없이 바로 돌려주고, 만료 {@link #TOKEN_REFRESH_AHEAD} 전부터는 한 스레드가 뒤에서 미리 갱신한다.
     * 이미 만료됐거나 아직 없으면 이 호출이 직접 교환한다 — 그때도 락을 쥐지 않아 다른 스레드가 이 호출을 기다리지 않는다
     * (만료 순간 동시에 오는 발송은 각자 교환한다 — 하루 한 번 안팎이고 스레드가 최대 10개라 받아들인다).
     */
    String current() {
        Instant now = clock.instant();
        AccessToken current = cached.get();
        if (current == null || !now.isBefore(current.expiresAt())) {
            return exchange(now).value();
        }
        if (!now.isBefore(current.expiresAt().minus(TOKEN_REFRESH_AHEAD)) && refreshingAhead.compareAndSet(false, true)) {
            Thread.startVirtualThread(this::refreshAhead);
        }
        return current.value();
    }

    private void refreshAhead() {
        try {
            exchange(clock.instant());
        } catch (RuntimeException e) {
            log.warn("[fcm] 접근 토큰 미리 갱신이 실패해 만료 때 다시 받는다 — {}", e.getMessage());
        } finally {
            refreshingAhead.set(false);
        }
    }

    /** 서비스 계정 JWT 를 접근 토큰으로 바꿔 보관한다 — 일시 장애로 센다(서킷). */
    private AccessToken exchange(Instant now) {
        String assertion = Jwts.builder()
                .issuer(clientEmail)
                .claim("scope", SCOPE)
                .claim("aud", tokenUri) // audience().single(String) 이 폐기 예정 — 배열이 아닌 문자열 aud 는 claim 으로 건다
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofHours(1))))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
        String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer",
                StandardCharsets.UTF_8) + "&assertion=" + assertion;
        HttpResponse<String> response = http.post(URI.create(tokenUri), "application/x-www-form-urlencoded", form, null);
        if (response.statusCode() != 200) {
            throw new IllegalStateException("FCM 접근 토큰 발급 실패 status=" + response.statusCode());
        }
        JsonNode node = JSON.readTree(response.body());
        AccessToken exchanged = new AccessToken(node.get("access_token").asString(),
                now.plusSeconds(node.get("expires_in").asLong()));
        cached.set(exchanged);
        return exchanged;
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
