package src.backend.global.security;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;

/**
 * JWT 발급·검증 담당(jjwt 0.12.6).
 * access(짧게)·refresh(길게) 두 종류를 발급하며, 토큰에 담는 정보는
 * subject=accountId, academyId, role, status, type(access/refresh) 이다.
 * secret·유효기간은 application.yml 의 jwt.* 에서 주입한다.
 *
 * <p>{@code role}·{@code status} 클레임은 각각 {@link Role#name()}·{@link AccountStatus#name()}
 * (대문자, 예: {@code "DRIVER"}·{@code "PENDING"})으로 저장한다 — {@link #resolveAuthUser} 가
 * {@code valueOf(String)} 으로 되읽으므로, 발급·복원 양쪽이 같은 표기를 쓰지 않으면 즉시
 * {@link IllegalArgumentException} 이 난다.
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

    public String createAccessToken(Long accountId, Long academyId, Role role, AccountStatus status) {
        return build(accountId, academyId, role, status, TYPE_ACCESS, accessValidityMs);
    }

    public String createRefreshToken(Long accountId, Long academyId, Role role, AccountStatus status) {
        return build(accountId, academyId, role, status, TYPE_REFRESH, refreshValidityMs);
    }

    /**
     * {@code jti}(id) 클레임이 반드시 필요하다 — 이것 없이는 같은 계정에 대해 같은 밀리초에 발급된
     * 두 토큰(subject·academyId·role·status·type·issuedAt·expiration 이 전부 같음)이 서명까지
     * 바이트 단위로 동일해져, {@code refresh_token.token_hash} UNIQUE 제약을 어기고 두 번째 저장이
     * {@code 500}으로 죽는다(Task 4 가 {@code AuthControllerTest} 로 재현·발견, 보고서 ⑤ — 로그인
     * 직후 곧바로 refresh 하는 것처럼 같은 계정에 대한 토큰 발급이 짧은 간격으로 겹치면 실제로도 날 수
     * 있는 운영 결함이었다).
     */
    private String build(Long accountId, Long academyId, Role role, AccountStatus status, String type,
            long validityMs) {
        Date now = new Date();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(accountId))
                .claim(CLAIM_ACADEMY_ID, academyId)
                .claim(CLAIM_ROLE, role.name())
                .claim(CLAIM_STATUS, status.name())
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

    /**
     * 검증된 클레임 → {@link AuthUser}. REST 필터·STOMP 인증 인터셉터가 공용으로 쓴다.
     *
     * <p>{@code role}·{@code status} 클레임이 없는 토큰은 여기서 {@link JwtException}으로 실패한다 —
     * 그런 토큰을 조용히 통과시키면 계정 상태 게이트가 상태 미상 요청 전체를 열어 버리므로, 늦게
     * 실패하는 것보다 여기서 바로 막는 편이 낫다(리뷰 라운드 1 Critical #1). {@code Enum.valueOf(Class,
     * null)}은 {@link IllegalArgumentException}이 아니라 {@link NullPointerException}을 던지는데,
     * 그 타입 그대로 호출부까지 새 나가면 {@link JwtAuthenticationFilter}·{@link
     * StompAuthChannelInterceptor}의 {@code catch (JwtException | IllegalArgumentException e)} 절이
     * 놓쳐 {@code GlobalExceptionHandler}가 못 잡는 API_SPEC §1.10 형식 밖 500이 된다(리뷰 라운드 2
     * Important #10). 그래서 여기서 {@link IllegalArgumentException}·{@link NullPointerException}을
     * 전부 붙잡아 {@link JwtException}으로 통일한다 — 호출부 catch 절을 그대로 둬도 계속 막힌다.
     */
    public AuthUser resolveAuthUser(Claims claims) {
        try {
            Long accountId = Long.valueOf(claims.getSubject());
            Number academyId = claims.get(CLAIM_ACADEMY_ID, Number.class);
            Role role = Role.valueOf(claims.get(CLAIM_ROLE, String.class));
            AccountStatus status = AccountStatus.valueOf(claims.get(CLAIM_STATUS, String.class));
            return new AuthUser(accountId, academyId == null ? null : academyId.longValue(), role, status);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new JwtException("토큰 클레임을 해석할 수 없습니다", e);
        }
    }
}
