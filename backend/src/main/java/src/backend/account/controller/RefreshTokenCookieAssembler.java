package src.backend.account.controller;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * {@code refresh_token} 쿠키를 만드는 유일한 지점(§1.2.1 · 브리프 §3.1) — 발급과 삭제가 속성 값
 * ({@code HttpOnly} · {@code Secure} · {@code SameSite=Strict} · {@code Path=/api/v1/auth})을 반드시
 * 똑같이 써야 브라우저가 같은 쿠키로 인식해 지운다(§2.7). 이 클래스 밖에서 {@link ResponseCookie}
 * 를 새로 만들지 않는다 — 만들면 두 속성 집합이 갈라질 위험이 생긴다.
 */
@Component
public class RefreshTokenCookieAssembler {

    static final String COOKIE_NAME = "refresh_token";
    private static final String COOKIE_PATH = "/api/v1/auth"; // Ruling 102 — API_SPEC §1.2.1 본문의 /api/auth 는 낡은 값
    private static final String SAME_SITE = "Strict";

    /** 로그인·refresh 성공 시 발급한다 — {@code maxAge} 는 refresh 토큰의 남은 유효기간(초)이다. */
    public ResponseCookie issue(String rawRefreshToken, long maxAgeSeconds) {
        return build(rawRefreshToken, maxAgeSeconds);
    }

    /** 로그아웃·비밀번호 변경 시 브라우저의 쿠키를 지운다 — {@code Max-Age=0}, 값은 비운다(§2.7). */
    public ResponseCookie delete() {
        return build("", 0);
    }

    private ResponseCookie build(String value, long maxAgeSeconds) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(true)
                .sameSite(SAME_SITE)
                .path(COOKIE_PATH)
                .maxAge(maxAgeSeconds)
                .build();
    }
}
