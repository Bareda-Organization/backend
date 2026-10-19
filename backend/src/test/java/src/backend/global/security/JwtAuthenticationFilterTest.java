package src.backend.global.security;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import java.time.Clock;
import java.util.stream.Stream;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.CannotCreateTransactionException;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;

/**
 * {@link JwtAuthenticationFilter} 가 {@link JwtTokenProvider#resolveAuthUser} 의 실패를 실제로
 * 삼키는지 확인한다. {@code JwtTokenProviderTest} 는 원천이 올바른
 * 타입({@link io.jsonwebtoken.JwtException})을 던지는지만 보고, 필터의 {@code catch} 절이 그것을
 * 실제로 붙잡아 보호 자원 접근을 401 로 이어가는지는 이 테스트가 본다 — 원천만 고치고 호출부
 * 동작을 확인하지 않으면 "예외 타입만 바꾼" 것이 된다.
 *
 * <p>{@link JwtTokenProvider} 는 항상 완전한 클레임만 발급하므로, 발급 경로로는 클레임 누락 토큰을
 * 만들 수 없다 — 리플렉션으로 내부 서명 키를 꺼내 직접 서명해, 발급 경로의 결함(예: 로그인·토큰 재발급
 * 쪽이 클레임을 빠뜨리는 경우)을 흉내 낸다.
 */
@WebMvcTest(controllers = JwtAuthFilterTestController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, src.backend.global.config.ClockConfig.class})
class JwtAuthenticationFilterTest {

    /** 필터가 계정 현재 상태를 묻는 상대 — 이 시험은 상태 판정이 아니라 토큰 처리를 보므로 "권한 잃지 않음" 으로 둔다(Ruling 880 은 AccountStandingHttpTest). */
    @MockitoBean
    private AccountStanding accountStanding;

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

    /**
     * 계정 상태 조회(Ruling 880)가 DB 자원 오류로 실패해도 §1.10 규격의 {@code 503 SERVER_BUSY} + {@code Retry-After} 다 —
     * 필터는 컨트롤러 어드바이스 바깥이라 잡지 않으면 예외가 서블릿 컨테이너로 새어 규격 없는 500 이 된다.
     * 모르는 상태를 통과시키지도 않는다(상태 조회가 실패하면 인증을 세우지 않는다).
     */
    @ParameterizedTest
    @MethodSource("dbResourceFailures")
    void 계정_상태_조회가_DB_자원_오류로_실패하면_503_SERVER_BUSY_다(RuntimeException failure) throws Exception {
        String access = tokenProvider.createAccessToken(7L, 1L, Role.PARENT, AccountStatus.ACTIVE);
        when(accountStanding.isLost(7L)).thenThrow(failure);

        mockMvc.perform(get("/api/v1/jwt-filter-test/protected").header("Authorization", "Bearer " + access))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "3"))
                .andExpect(jsonPath("$.error.code").value("SERVER_BUSY"));
    }

    private static Stream<RuntimeException> dbResourceFailures() {
        return Stream.of(new CannotCreateTransactionException("풀 연결 대기 초과"),
                new DataAccessResourceFailureException("연결 끊김"), new QueryTimeoutException("쿼리 취소"),
                new CannotAcquireLockException("잠금 대기 초과"));
    }

    /**
     * 계정 상태 조회가 <b>자원 오류가 아닌</b> DB 예외(SQL 문법 오류 · 교착)로 실패하면 503 이 아니라 {@code 500 INTERNAL_ERROR} 다 —
     * 코드 결함까지 "잠시 후 다시 시도" 로 보이면 안 된다. 어느 쪽이든 모르는 상태를 통과시키지 않는다(200 이 아니다).
     * 자원 오류의 범위는 {@code GlobalExceptionHandler} 와 같은 {@link src.backend.global.error.DbResourceFailure} 한 곳이다.
     */
    @ParameterizedTest
    @MethodSource("dbCodeDefects")
    void 계정_상태_조회가_자원_오류가_아닌_DB_예외로_실패하면_500_이고_통과시키지_않는다(RuntimeException failure) throws Exception {
        String access = tokenProvider.createAccessToken(7L, 1L, Role.PARENT, AccountStatus.ACTIVE);
        when(accountStanding.isLost(7L)).thenThrow(failure);

        mockMvc.perform(get("/api/v1/jwt-filter-test/protected").header("Authorization", "Bearer " + access))
                .andExpect(status().isInternalServerError())
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
    }

    private static Stream<RuntimeException> dbCodeDefects() {
        return Stream.of(new BadSqlGrammarException("select", "SELEC 1", new SQLException("syntax error", "42601")),
                new CannotAcquireLockException("could not obtain lock", new SQLException("deadlock detected", "40P01")));
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
