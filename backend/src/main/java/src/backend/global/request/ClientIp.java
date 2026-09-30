package src.backend.global.request;

import java.net.InetAddress;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 감사 기록의 접속 {@code ip}(BR-062 · R46 감사 C) — 로그인 기록과 조회 기록이 같은 방식으로 뽑는다.
 *
 * <p>{@code X-Real-IP} 를 쓰고 {@code X-Forwarded-For} 는 쓰지 않는다 — nginx 는 받은 {@code X-Forwarded-For}
 * 뒤에 실제 주소를 덧붙이므로 첫 값은 요청자가 적은 값이라 위조 가능하다. {@code X-Real-IP} 는 nginx 가
 * {@code $remote_addr} 로 항상 덮어쓴다({@code infra/proxy/nginx*.conf}). IP 표기가 아니면 {@code null} 이다 —
 * {@code audit_log.ip} 가 {@code inet} 이라 그대로 저장하면 요청 자체가 500 이 된다.
 */
public final class ClientIp {

    private ClientIp() {
    }

    /** 요청의 접속 IP. 표기가 IP 가 아니면 {@code null}. */
    public static String of(HttpServletRequest request) {
        String realIp = request.getHeader("X-Real-IP");
        String candidate = realIp != null && !realIp.isBlank() ? realIp.trim() : request.getRemoteAddr();
        try {
            return InetAddress.ofLiteral(candidate).getHostAddress();
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }

    /** 지금 스레드가 처리 중인 요청의 접속 IP. 요청 밖(배치·시험)이면 {@code null}. */
    public static String ofCurrentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? of(attributes.getRequest())
                : null;
    }
}
