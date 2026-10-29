package src.backend.global.request;

import java.net.Inet6Address;
import java.net.InetAddress;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 감사 기록의 접속 {@code ip}(BR-062) — 로그인 기록과 조회 기록이 같은 방식으로 뽑는다.
 *
 * <p>{@code X-Real-IP} 를 쓰고 {@code X-Forwarded-For} 는 쓰지 않는다 — nginx 는 받은 {@code X-Forwarded-For}
 * 뒤에 실제 주소를 덧붙이므로 첫 값은 요청자가 적은 값이라 위조 가능하다. {@code X-Real-IP} 는 nginx 가
 * {@code $remote_addr} 로 항상 덮어쓴다({@code infra/proxy/nginx*.conf}). 그 헤더는 <b>접속 상대가 프록시(루프백 · 사설 · 링크 로컬 주소)일 때만</b> 믿는다 —
 * 공인 주소에서 직접 온 요청의 헤더는 요청자가 적은 값이다. 헤더가 없거나 IP 표기가 아니면 접속 주소를 쓴다. 접속 주소도 IP 표기가 아니면 {@code null} 이다 —
 * {@code audit_log.ip} 가 {@code inet} 이라 그대로 저장하면 요청 자체가 500 이 된다.
 */
public final class ClientIp {

    private ClientIp() {
    }

    /** 요청의 접속 IP. 표기가 IP 가 아니면 {@code null}. */
    public static String of(HttpServletRequest request) {
        String peer = literal(request.getRemoteAddr());
        String forwarded = isProxy(peer) ? literal(request.getHeader("X-Real-IP")) : null;
        return forwarded != null ? forwarded : peer;
    }

    /** 접속 상대가 같은 호스트 · 같은 사설망의 프록시인가. */
    private static boolean isProxy(String peer) {
        if (peer == null) {
            return false;
        }
        InetAddress address = InetAddress.ofLiteral(peer);
        boolean uniqueLocalV6 = address instanceof Inet6Address && (address.getAddress()[0] & 0xFE) == 0xFC;
        return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress() || uniqueLocalV6;
    }

    /** 문자열을 IP 주소 표기로 정규화한다 — 비었거나 IP 표기가 아니면 {@code null}. */
    private static String literal(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return InetAddress.ofLiteral(text.trim()).getHostAddress();
        } catch (IllegalArgumentException e) {
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
