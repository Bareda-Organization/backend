package src.backend.global.security;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import src.backend.global.common.enums.Role;

import src.backend.global.common.enums.Role;

/**
 * JWT 발급·검증 담당(jjwt 0.12.6).
 * access(짧게)·refresh(길게) 두 종류를 발급하며, 토큰에 담는 정보는
 * subject=accountId, academyId, role, status, type(access/refresh) 이다.
 * secret·유효기간은 application.yml 의 jwt.* 에서 주입한다.
 *
 * <p>{@code role} 클레임은 {@link Role#name()}(대문자, 예: {@code "DRIVER"})으로 저장한다 —
 * {@link #resolveAuthUser} 가 {@link Role#valueOf(String)} 으로 되읽으므로, 발급·복원 양쪽이
 * 같은 표기를 쓰지 않으면 즉시 {@link IllegalArgumentException} 이 난다.
 */
@Component
public class JwtTokenProvider {

    private static final String CLAIM_ACADEMY_ID = "academyId";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_STATUS = "status";
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

    public String createAccessToken(Long accountId, Long academyId, Role role, String status) {
        return build(accountId, academyId, role, status, TYPE_ACCESS, accessValidityMs);
    }

    public String createRefreshToken(Long accountId, Long academyId, Role role, String status) {
        return build(accountId, academyId, role, status, TYPE_REFRESH, refreshValidityMs);
    }

    private String build(Long accountId, Long academyId, Role role, String status, String type, long validityMs) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(accountId))
                .claim(CLAIM_ACADEMY_ID, academyId)
                .claim(CLAIM_ROLE, role.name())
                .claim(CLAIM_STATUS, status)
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
    public AuthUser resolveAuthUser(Claims claims) {
        Long accountId = Long.valueOf(claims.getSubject());
        Number academyId = claims.get(CLAIM_ACADEMY_ID, Number.class);
        Role role = Role.valueOf(claims.get(CLAIM_ROLE, String.class));
        String status = claims.get(CLAIM_STATUS, String.class);
        return new AuthUser(accountId, academyId == null ? null : academyId.longValue(), role, status);
    }
}
