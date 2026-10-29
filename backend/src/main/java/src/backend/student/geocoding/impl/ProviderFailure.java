package src.backend.student.geocoding.impl;

import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * 공급자 호출 실패를 값(주소 · 검색어)이 없는 한 줄로 옮긴다 — 예외 메시지와 원인 사슬은 로그로 그대로 나가는데 원인 예외의 메시지에는 요청 URL 이 실려
 * 있다. 실패 종류(원인 클래스 · HTTP 상태)만 남긴다.
 */
final class ProviderFailure {

    private ProviderFailure() {
    }

    /** 원인 클래스 이름, HTTP 응답이 있었으면 그 상태 코드를 덧붙인다. */
    static String describe(Throwable cause) {
        String name = cause.getClass().getSimpleName();
        return cause instanceof WebClientResponseException http ? name + " HTTP " + http.getStatusCode().value() : name;
    }
}
