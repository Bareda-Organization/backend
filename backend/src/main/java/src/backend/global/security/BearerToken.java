package src.backend.global.security;

/** {@code Authorization: Bearer <토큰>} 헤더에서 토큰을 꺼낸다 — HTTP 필터와 STOMP CONNECT 가 같은 규칙을 쓴다. */
public final class BearerToken {

    /** 토큰을 싣는 요청 헤더 이름. */
    public static final String HEADER = "Authorization";

    private static final String PREFIX = "Bearer ";

    private BearerToken() {
    }

    /** 헤더 값에서 토큰을 꺼낸다 — 헤더가 없거나 {@code Bearer } 형식이 아니거나 토큰이 비면 {@code null}. */
    public static String from(String header) {
        if (header == null || !header.startsWith(PREFIX)) {
            return null;
        }
        String token = header.substring(PREFIX.length());
        return token.isBlank() ? null : token;
    }
}
