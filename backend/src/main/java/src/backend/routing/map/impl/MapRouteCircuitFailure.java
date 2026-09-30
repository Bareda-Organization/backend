package src.backend.routing.map.impl;

import java.util.function.Predicate;

import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * {@code mapRoute} 서킷이 <b>실패로 세는 것</b> — {@link TransientMapRouteFailure}(재시도 대상)에 더해
 * 일일 한도 소진(429)을 센다(BR-208, Ruling 379 ⑤). 429 는 다시 불러도 답이 같아 재시도는 하지 않지만(BR-050),
 * 공급자 쪽 사정이라 서킷이 열려야 같은 날 남은 헛호출이 멈춘다(Ruling 361). 4xx 중 나머지와 "경로 없음" 은
 * 요청 데이터 탓이라 그대로 세지 않는다.
 */
public class MapRouteCircuitFailure implements Predicate<Throwable> {

    private final TransientMapRouteFailure transientFailure = new TransientMapRouteFailure();

    @Override
    public boolean test(Throwable thrown) {
        return transientFailure.test(thrown) || isQuotaExhausted(thrown);
    }

    /** 원인 사슬 어디에든 429 응답이 있으면 한도 소진이다 — {@code block()} 이 예외를 감싸 올린다. */
    private static boolean isQuotaExhausted(Throwable thrown) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof WebClientResponseException response) {
                return response.getStatusCode().isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS);
            }
        }
        return false;
    }
}
