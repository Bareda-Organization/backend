package src.backend.routing.map.impl;

import java.io.IOException;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * 도로 경로 호출 실패 중 <b>다시 부르면 결과가 달라질 수 있는 것</b>만 재시도 대상으로 고른다(BR-050,
 * {@code application.yml} 의 {@code retry-exception-predicate}).
 *
 * <p>대상 — 타임아웃 · 연결 실패 · 5xx. 대상 밖 — 4xx(잘못된 좌표·키 오류)와 "경로 없음" 응답: 같은 요청을
 * 다시 보내도 답이 같아 일일 할당량만 시도 횟수 배로 쓴다. 대상 밖 실패도 폴백(직선거리 근사)으로는 그대로
 * 이어진다.
 */
public class TransientMapRouteFailure implements Predicate<Throwable> {

    /** {@code block()} 이 타임아웃을 감싸 올리므로 원인 사슬 전체를 본다. */
    @Override
    public boolean test(Throwable thrown) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof WebClientResponseException response) {
                return response.getStatusCode().is5xxServerError();
            }
            if (cause instanceof TimeoutException || cause instanceof WebClientRequestException
                    || cause instanceof IOException) {
                return true;
            }
        }
        return false;
    }
}
