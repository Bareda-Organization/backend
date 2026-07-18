package src.backend.global.security;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * JWT 발급·검증 담당(jjwt 0.12.6).
 * access(짧게)·refresh(길게) 두 종류를 발급하며, 토큰에 담는 정보는
 * subject=userId, email, memberships("tenantId:ROLE" 목록), type(access/refresh) 이다.
 * secret·유효기간은 application.yml 의 jwt.* 에서 주입한다.
 */
@Component
public class JwtTokenProvider {

    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_MEMBERSHIPS = "memberships";
    private static final String CLAIM_TYPE = "type";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    private final SecretKey key;
    private final long accessValidityMs;
    private final long refreshValidityMs;

    public JwtTokenProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-validity-seconds}") long accessValiditySeconds,
            @Value("${jwt.refresh-token-validity-seconds}") long refreshValiditySeconds) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessValidityMs = accessValiditySeconds * 1000;
        this.refreshValidityMs = refreshValiditySeconds * 1000;
    }

    public String createAccessToken(Long userId, String email, List<String> memberships) {
        return build(userId, email, memberships, TYPE_ACCESS, accessValidityMs);
    }

    public String createRefreshToken(Long userId, String email, List<String> memberships) {
        return build(userId, email, memberships, TYPE_REFRESH, refreshValidityMs);
    }

    private String build(Long userId, String email, List<String> memberships, String type, long validityMs) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_EMAIL, email)
                .claim(CLAIM_MEMBERSHIPS, memberships)
                .claim(CLAIM_TYPE, type)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + validityMs))
                .signWith(key)
                .compact();
    }

    /** 서명·만료를 검증하고 클레임을 돌려준다. 실패 시 {@link io.jsonwebtoken.JwtException}. */
    public Claims parse(String token) {
        Jws<Claims> jws = Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
        return jws.getPayload();
    }

    public boolean isAccessToken(Claims claims) {
        return TYPE_ACCESS.equals(claims.get(CLAIM_TYPE, String.class));
    }

    public boolean isRefreshToken(Claims claims) {
        return TYPE_REFRESH.equals(claims.get(CLAIM_TYPE, String.class));
    }

    /** 검증된 클레임 → {@link AuthUser}. REST 필터·STOMP 인증 인터셉터가 공용으로 쓴다. */
    @SuppressWarnings("unchecked")
    public AuthUser resolveAuthUser(Claims claims) {
        Long userId = Long.valueOf(claims.getSubject());
        String email = claims.get(CLAIM_EMAIL, String.class);
        List<String> memberships = claims.get(CLAIM_MEMBERSHIPS, List.class);
        return AuthUser.fromEncoded(userId, email, memberships);
    }
}
