package src.backend.notification.push.impl;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * FCM · OAuth 서버로 나가는 HTTP 호출 하나 — 연결·요청 시간 상한({@link FcmPushSender#TIMEOUT})을 두 호출이 같이 쓴다. 전송 실패는 모두 {@link IllegalStateException}
 * 으로 옮겨 서킷이 일시 장애로 센다.
 */
final class FcmHttp {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(FcmPushSender.TIMEOUT).build();

    HttpResponse<String> post(URI uri, String contentType, String body, String bearer) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(FcmPushSender.TIMEOUT)
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
}
