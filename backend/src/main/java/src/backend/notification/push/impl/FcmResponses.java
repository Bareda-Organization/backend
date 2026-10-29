package src.backend.notification.push.impl;

import java.net.http.HttpResponse;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * FCM 응답 분류 — 일시 장애(다시 보내면 도착할 수 있음) · 무효 토큰(해지 대상) · 본문 거절. {@link FcmPushSender} 의 발송 결과 처리가 쓴다.
 */
final class FcmResponses {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private FcmResponses() {
    }

    /** 429 · 5xx — FCM 쪽 사정이라 다시 보내면 도착할 수 있다. */
    static boolean isTransient(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }

    /**
     * 404 {@code UNREGISTERED} · 400 {@code INVALID_ARGUMENT} — 그 토큰으로는 다시 보내도 도착하지 않는다.
     * 단 {@code INVALID_ARGUMENT} 는 본문(제목·본문·{@code data}) 이 거부돼도 같은 코드라서(BR-221), 위반 필드가
     * 밝혀졌고 {@code message.token} 이 아니면 토큰 탓이 아니므로 해지하지 않고 실패로 남겨 재시도에 맡긴다.
     */
    static boolean isInvalidToken(HttpResponse<String> response) {
        String body = response.body() == null ? "" : response.body();
        if (response.statusCode() == 404) {
            return body.contains("UNREGISTERED");
        }
        return response.statusCode() == 400 && body.contains("INVALID_ARGUMENT") && !blamesOtherThanToken(body);
    }

    /** 응답의 {@code fieldViolations} 가 있고 그 어느 것도 {@code message.token} 이 아니면 본문 문제다. */
    static boolean blamesOtherThanToken(String body) {
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
            return false; // 읽을 수 없는 본문은 토큰 오류로 본다
        }
    }
}
