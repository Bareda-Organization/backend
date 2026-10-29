package src.backend.notification.push.impl;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import tools.jackson.databind.json.JsonMapper;

import src.backend.notification.entity.DeviceToken;
import src.backend.notification.push.spec.PushMessage;
import src.backend.notification.push.spec.PushNotAttemptedException;
import src.backend.notification.push.spec.PushSender;
import src.backend.notification.repository.DeviceTokenRepository;

/**
 * FCM HTTP v1 한 채널 발송(Ruling 331 — android·ios(Firebase 가 APNs 로 중계)·web). 수신 계정의 유효한 전 단말에
 * 보내고, FCM 이 무효라고 답한 토큰({@code UNREGISTERED}·{@code INVALID_ARGUMENT})은 해지한다(API_SPEC §2.11).
 *
 * <p>인증은 서비스 계정 키로 서명한 JWT 를 접근 토큰으로 바꾸는 OAuth2 흐름이다 — 새 의존성 없이 이미 쓰는 jjwt 로
 * 서명하고 표준 {@link HttpClient} 로 교환한다. 접근 토큰은 만료 5분 전부터 뒤에서 미리 갱신하고 그동안 옛 토큰을 쓴다
 * (교환을 락 안에서 하면 FCM 인증 서버가 느린 동안 전 발송 스레드가 줄을 선다).
 *
 * <p><b>서킷</b>({@code resilience4j} 인스턴스 {@code fcm} — 값은 {@code placeSearch} 와 같다)은 <b>일시 장애</b>(네트워크 ·
 * 429 · 5xx · 접근 토큰 발급 실패)만 센다. 열리면 호출 없이 즉시 거절하고({@link PushNotAttemptedException} — 시도 횟수에 넣지 않는다), 행은 {@code pending} 으로 남아 아웃박스 워커가
 * 이어받는다. 본문·단말 문제의 4xx 는 FCM 이 멀쩡하다는 뜻이라 세지 않는다 — 잘못된 알림 하나가 전 발송을 막으면 안 된다.
 * 서킷은 FCM 호출만 감싼다 — 단말 조회·무효 토큰 해지의 DB 오류는 FCM 에 닿지 않은 실패라 밖에 둔다(BR-318).
 *
 * <p>일시 장애(429·5xx·네트워크)는 예외로 알려 아웃박스 재시도에 맡긴다({@link PushSender} 계약).
 * ponytail: 재시도는 행 단위라 여러 단말 중 일부만 실패해도 성공한 단말에 다시 보낸다 — 단말별 발송 기록이 필요해지면
 * 그때 나눈다.
 */
@Component
@ConditionalOnProperty(name = "app.push.sender", havingValue = "fcm")
public class FcmPushSender implements PushSender {

    /** {@code resilience4j.circuitbreaker.instances} 의 이름 — 값은 장소 검색({@code placeSearch})과 같다. */
    static final String RESILIENCE_INSTANCE = "fcm";

    /**
     * 연결·요청 시간 상한 — 시험이 값을 못박는다({@code FcmPushSenderTest}). 응답이 100~300ms 가 정상이라 4초면 충분하고,
     * 10초면 FCM 이 응답하지 않을 때 발송 스레드 8개가 모두 10초씩 묶여 처리율이 0.8건/s 로 떨어진다.
     */
    public static final Duration TIMEOUT = Duration.ofSeconds(4);

    private static final Logger log = LoggerFactory.getLogger(FcmPushSender.class);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final URI sendUri;

    private final DeviceTokenRepository deviceTokenRepository;

    private final Clock clock;

    private final CircuitBreaker circuitBreaker;

    private final FcmHttp http = new FcmHttp();

    private final FcmAccessTokens accessTokens;

    /** 자격 증명 3개는 기본값이 없다 — prod 에서 빠지면 기동이 실패한다(SSM 주입, Ruling 331). */
    public FcmPushSender(@Value("${app.push.fcm.project-id}") String projectId,
            @Value("${app.push.fcm.client-email}") String clientEmail,
            @Value("${app.push.fcm.private-key}") String privateKeyPem,
            @Value("${app.push.fcm.base-url:https://fcm.googleapis.com}") String baseUrl,
            @Value("${app.push.fcm.token-uri:https://oauth2.googleapis.com/token}") String tokenUri,
            DeviceTokenRepository deviceTokenRepository, Clock clock, CircuitBreakerRegistry circuitBreakers) {
        this.accessTokens = new FcmAccessTokens(clientEmail, privateKeyPem, tokenUri, clock, http);
        this.sendUri = URI.create(baseUrl + "/v1/projects/" + projectId + "/messages:send");
        this.deviceTokenRepository = deviceTokenRepository;
        this.clock = clock;
        this.circuitBreaker = circuitBreakers.circuitBreaker(RESILIENCE_INSTANCE);
    }

    /** 유효한 전 단말에 보낸다 — 단말이 없으면 보낼 곳이 없어 아무것도 하지 않는다(인앱 목록에는 행이 남는다). */
    @Override
    public void send(PushMessage message) {
        List<DeviceToken> tokens = deviceTokenRepository.findAllByAccountIdAndRevokedAtIsNull(
                message.recipientAccountId());
        List<Long> invalidTokenIds = new ArrayList<>();
        List<String> rejections;
        try {
            rejections = circuitBreaker.executeSupplier(() -> sendToAll(message, tokens, invalidTokenIds));
        } catch (CallNotPermittedException e) {
            throw new PushNotAttemptedException("FCM 차단 장치가 열려 호출하지 않았다", e);
        } finally {
            revoke(invalidTokenIds); // 다른 단말의 일시 장애로 예외가 나도 이미 확인한 무효 토큰은 해지한다
        }
        if (!rejections.isEmpty()) {
            throw new IllegalStateException("FCM 발송 실패 " + rejections);
        }
    }

    /**
     * 단말마다 보내고 FCM 이 거절한 4xx(본문 문제 등)를 모아 돌려준다. <b>일시 장애는 여기서 던진다</b> — 서킷이 센다.
     * 4xx 는 값으로 돌려줘 서킷 밖에서 실패 처리하고, 무효로 답한 토큰의 id 는 {@code invalidTokenIds} 에 모아 DB 해지를
     * 서킷 밖에 맡긴다.
     */
    private List<String> sendToAll(PushMessage message, List<DeviceToken> tokens, List<Long> invalidTokenIds) {
        List<String> transientFailures = new ArrayList<>();
        List<String> rejections = new ArrayList<>();
        for (DeviceToken token : tokens) {
            HttpResponse<String> response = http.post(sendUri, "application/json",
                    payloadOf(message, token.getToken()), accessTokens.current());
            if (response.statusCode() == 200) {
                continue;
            }
            if (FcmResponses.isInvalidToken(response)) {
                invalidTokenIds.add(token.getId());
                continue;
            }
            (FcmResponses.isTransient(response.statusCode()) ? transientFailures : rejections).add("status=" + response.statusCode());
        }
        if (!transientFailures.isEmpty()) {
            throw new IllegalStateException("FCM 발송 실패 " + transientFailures + rejections);
        }
        return rejections;
    }

    private void revoke(List<Long> invalidTokenIds) {
        for (Long tokenId : invalidTokenIds) {
            deviceTokenRepository.revokeInvalid(tokenId, OffsetDateTime.now(clock));
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

}
