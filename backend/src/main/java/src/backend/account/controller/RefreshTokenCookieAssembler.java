package src.backend.account.controller;

import java.util.Locale;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import src.backend.account.command.LoginResult;
import src.backend.account.command.RefreshResult;
import src.backend.account.dto.LoginResponse;
import src.backend.account.dto.RefreshResponse;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.response.ApiResponse;

/**
 * {@code refresh_token} 쿠키를 만드는 유일한 지점(§1.2.1 · 브리프 §3.1) — 발급과 삭제가 속성 값
 * ({@code HttpOnly} · {@code Secure} · {@code SameSite=Strict} · {@code Path=/api/v1/auth})을 반드시
 * 똑같이 써야 브라우저가 같은 쿠키로 인식해 지운다(§2.7). 이 클래스 밖에서 {@link ResponseCookie}
 * 를 새로 만들지 않는다 — 만들면 두 속성 집합이 갈라질 위험이 생긴다.
 */
@Component
public class RefreshTokenCookieAssembler {

    static final String COOKIE_NAME = "refresh_token";
    private static final String COOKIE_PATH = "/api/v1/auth"; // 베이스 경로 접두사 포함 — 그래야 /api/v1/auth/refresh 에 동봉된다(API_SPEC §1.2.1)
    private static final String SAME_SITE = "Strict";

    /** 로그인·refresh 성공 시 발급한다 — {@code maxAge} 는 refresh 토큰의 남은 유효기간(초)이다. */
    public ResponseCookie issue(String rawRefreshToken, long maxAgeSeconds) {
        return build(rawRefreshToken, maxAgeSeconds);
    }

    /** 로그아웃·비밀번호 변경 시 브라우저의 쿠키를 지운다 — {@code Max-Age=0}, 값은 비운다(§2.7). */
    public ResponseCookie delete() {
        return build("", 0);
    }

    /**
     * 요청이 내민 refresh 토큰 — 쿠키를 먼저 보고 없으면 본문을 본다(§2.6·§2.7). 어디에도 없으면 서비스를 부르지 않고 {@code 401
     * TOKEN_EXPIRED} 다.
     */
    public static String presentedToken(String cookieToken, String bodyToken) {
        String token = StringUtils.hasText(cookieToken) ? cookieToken : bodyToken;
        if (!StringUtils.hasText(token)) {
            throw new BusinessException(ErrorCode.TOKEN_EXPIRED);
        }
        return token;
    }

    /**
     * 로그인 응답(§2.5) — 웹이면 refresh 토큰을 본문에서 빼고 {@code Set-Cookie} 로만 내린다(§1.2.1). {@code academy} 는
     * {@code system_admin} 이면 키는 있고 값이 {@code null} 이다.
     */
    public ResponseEntity<ApiResponse<LoginResponse>> loginResponse(LoginResult result, boolean isWeb) {
        LoginResponse.Academy academy = result.role().hasPlatformScope() ? null
                : new LoginResponse.Academy(String.valueOf(result.academyId()), result.academyName(),
                        result.academyContact());
        LoginResponse body = new LoginResponse(result.accessToken(), isWeb ? null : result.refreshToken(),
                result.role().name().toLowerCase(Locale.ROOT), result.status().name().toLowerCase(Locale.ROOT),
                String.valueOf(result.accountId()), academy, result.mustChangePassword());
        return withRefreshCookie(body, isWeb, result.refreshToken(), result.refreshTokenValiditySeconds());
    }

    /** 재발급 응답(§2.6) — 웹이면 새 refresh 토큰을 쿠키로만 내린다. */
    public ResponseEntity<ApiResponse<RefreshResponse>> refreshResponse(RefreshResult result, boolean isWeb) {
        RefreshResponse body = new RefreshResponse(result.accessToken(), isWeb ? null : result.refreshToken());
        return withRefreshCookie(body, isWeb, result.refreshToken(), result.refreshTokenValiditySeconds());
    }

    /** 본문 없는 성공 응답(§2.7·§2.8) — 웹이면 쿠키 삭제 지시를 함께 돌려준다. */
    public ResponseEntity<Void> noContent(boolean isWeb) {
        ResponseEntity.HeadersBuilder<?> builder = ResponseEntity.noContent();
        if (isWeb) {
            builder.header(HttpHeaders.SET_COOKIE, delete().toString());
        }
        return builder.build();
    }

    private <T> ResponseEntity<ApiResponse<T>> withRefreshCookie(T body, boolean isWeb, String rawRefreshToken,
            long maxAgeSeconds) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (isWeb) {
            builder.header(HttpHeaders.SET_COOKIE, issue(rawRefreshToken, maxAgeSeconds).toString());
        }
        return builder.body(ApiResponse.ok(body));
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
