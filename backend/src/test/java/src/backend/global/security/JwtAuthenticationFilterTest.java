package src.backend.global.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;

/**
 * {@link JwtAuthenticationFilter} 가 {@link JwtTokenProvider#resolveAuthUser} 의 실패를 실제로
 * 삼키는지 확인한다(리뷰 라운드 2 Important #10). {@code JwtTokenProviderTest} 는 원천이 올바른
 * 타입({@link io.jsonwebtoken.JwtException})을 던지는지만 보고, 필터의 {@code catch} 절이 그것을
 * 실제로 붙잡아 보호 자원 접근을 401 로 이어가는지는 이 테스트가 본다 — 원천만 고치고 호출부
 * 동작을 확인하지 않으면 "예외 타입만 바꾼" 것이 된다.
 *
 * <p>{@link JwtTokenProvider} 는 항상 완전한 클레임만 발급하므로, 발급 경로로는 클레임 누락 토큰을
 * 만들 수 없다 — 리플렉션으로 내부 서명 키를 꺼내 직접 서명해, 발급 경로의 결함(예: Task 4 가
 * 클레임을 빠뜨리는 경우)을 흉내 낸다.
 */
@WebMvcTest(controllers = JwtAuthFilterTestController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, src.backend.global.config.ClockConfig.class})
class JwtAuthenticationFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Test
    void status_클레임이_없는_토큰으로_보호_자원을_호출하면_인증_없이_통과돼_401_이다() throws Exception {
        String token = signedTokenMissing("status");

        mockMvc.perform(get("/api/v1/jwt-filter-test/protected").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void role_클레임이_없는_토큰으로_보호_자원을_호출하면_인증_없이_통과돼_401_이다() throws Exception {
        String token = signedTokenMissing("role");

        mockMvc.perform(get("/api/v1/jwt-filter-test/protected").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    /**
     * BR-033 — 401 도 §1.10 공통 본문을 싣는다. 부재는 로그인부터, 만료는 재발급 시도로 클라이언트의
     * 다음 동작이 갈리므로(§8.1) {@code error.code} 가 없으면 관계자 웹의 자동 재발급이 동작하지 않는다.
     */
    @Test
    void 토큰_없이_보호_자원을_호출하면_401_본문에_UNAUTHORIZED_가_실린다() throws Exception {
        mockMvc.perform(get("/api/v1/jwt-filter-test/protected"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void 만료된_access_토큰은_401_TOKEN_EXPIRED_다() throws Exception {
        String expired = new JwtTokenProvider(jwtSecret, -60, 60, Clock.systemUTC())
                .createAccessToken(7L, 1L, Role.PARENT, AccountStatus.ACTIVE);

        mockMvc.perform(get("/api/v1/jwt-filter-test/protected").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_EXPIRED"));
    }

    /** BR-103 — 서명 검증이 빠지면 아무 키로 만든 토큰이 통과한다. */
    @Test
    void 다른_키로_서명한_토큰은_401_UNAUTHORIZED_다() throws Exception {
        String forged = new JwtTokenProvider("forged-secret-forged-secret-forged-secret-0123", 900, 60, Clock.systemUTC())
                .createAccessToken(7L, 1L, Role.PARENT, AccountStatus.ACTIVE);

        mockMvc.perform(get("/api/v1/jwt-filter-test/protected").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    /** BR-103 — 14일짜리 refresh 토큰을 access 자리에 쓰면 거부돼야 한다(access 판별 분기). */
    @Test
    void refresh_토큰을_Bearer_로_쓰면_401_UNAUTHORIZED_다() throws Exception {
        String refresh = tokenProvider.createRefreshToken(7L, 1L, Role.PARENT, AccountStatus.ACTIVE);

        mockMvc.perform(get("/api/v1/jwt-filter-test/protected").header("Authorization", "Bearer " + refresh))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    /** 대조군 — 유효한 access 토큰이면 통과해야 위 거부들이 "무조건 401" 이 아님을 안다. */
    @Test
    void 유효한_access_토큰은_보호_자원을_통과한다() throws Exception {
        String access = tokenProvider.createAccessToken(7L, 1L, Role.PARENT, AccountStatus.ACTIVE);

        mockMvc.perform(get("/api/v1/jwt-filter-test/protected").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk());
    }

    /** {@code missingClaim} 하나만 빼고 나머지는 정상 발급 형태로 채워 직접 서명한다. */
    private String signedTokenMissing(String missingClaim) {
        SecretKey key = (SecretKey) ReflectionTestUtils.getField(tokenProvider, "key");
        JwtBuilder builder = Jwts.builder().subject("7").claim("type", "access");
        if (!"academyId".equals(missingClaim)) {
            builder.claim("academyId", 1L);
        }
        if (!"role".equals(missingClaim)) {
            builder.claim("role", Role.PARENT.name());
        }
        if (!"status".equals(missingClaim)) {
            builder.claim("status", AccountStatus.ACTIVE.name());
        }
        return builder.signWith(key).compact();
    }
}
