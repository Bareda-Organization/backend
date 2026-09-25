package src.backend.global.request;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 요청 추적 식별자(API_SPEC §1.3 {@code X-Request-Id}) — 받으면 그대로, 없으면 만들어 응답 헤더와 로그
 * MDC({@code requestId})에 싣는다. 앱의 "처리되지 않았습니다"(C-10) 신고를 서버 로그의 그 요청과 잇는
 * 끈이다(BR-080).
 *
 * <p>시큐리티 체인보다 먼저 돌아야 401·403 거부 응답에도 실린다 — 그래서 최우선 순서다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    static final String MDC_KEY = "requestId";

    /** 로그에 그대로 찍히는 값이라 모양을 제한한다 — 줄바꿈 같은 문자로 로그 줄을 꾸며 넣지 못하게. */
    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = request.getHeader(HEADER);
        if (requestId == null || !ALLOWED.matcher(requestId).matches()) {
            requestId = UUID.randomUUID().toString();
        }
        response.setHeader(HEADER, requestId);
        MDC.put(MDC_KEY, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
