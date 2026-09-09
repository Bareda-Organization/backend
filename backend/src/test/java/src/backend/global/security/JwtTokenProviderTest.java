package src.backend.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;

/**
 * JWT 발급 → 검증 왕복 단위 테스트(스프링 컨텍스트 없이).
 * secret·유효기간을 직접 주입해 access/refresh 구분과 클레임 복원을 확인한다.
 */
class JwtTokenProviderTest {

    private final JwtTokenProvider provider = new JwtTokenProvider(
            "test-secret-key-for-jwt-that-is-at-least-32-bytes-long!!", 900, 1209600);

    @Test
    void access_token_round_trip() {
        String token = provider.createAccessToken(7L, 1L, Role.DRIVER, AccountStatus.ACTIVE);

        Claims claims = provider.parse(token);

        assertThat(claims.getSubject()).isEqualTo("7");
        assertThat(provider.isAccessToken(claims)).isTrue();
        assertThat(provider.isRefreshToken(claims)).isFalse();

        AuthUser principal = provider.resolveAuthUser(claims);
        assertThat(principal.academyId()).isEqualTo(1L);
        assertThat(principal.authorities()).extracting("authority").containsExactly("ROLE_DRIVER");
    }

    @Test
    void refresh_token_is_flagged_as_refresh() {
        String token = provider.createRefreshToken(7L, 1L, Role.DRIVER, AccountStatus.ACTIVE);

        Claims claims = provider.parse(token);

        assertThat(provider.isRefreshToken(claims)).isTrue();
        assertThat(provider.isAccessToken(claims)).isFalse();
    }

    /**
     * 같은 인자로 두 번 발급한 refresh 토큰은 서로 달라야 한다 — 같으면 두 단말이 <b>한 문자열</b>을
     * 나눠 갖게 되고, {@code refresh_token.token_hash} 가 UNIQUE 라 두 번째 로그인이 저장 단계에서
     * 실패한다. 값을 가르는 유일한 재료가 {@code jti} 이므로 이 단언이 곧 {@code jti} 회귀 단언이다.
     *
     * <p>인자를 완전히 같게 두는 것이 핵심이다 — 발급 시각(초 단위)까지 같아질 확률이 지배적인
     * 조건에서만 {@code jti} 부재가 확실히 드러난다. 로그인과 재발급을 견주는 통합 테스트는 두 호출이
     * 다른 밀리초에 떨어지면 {@code jti} 없이도 통과해, 느린 머신에서 변형이 살아남는다.
     */
    @Test
    void 같은_인자로_발급한_refresh_토큰_두_개는_서로_다르다() {
        String first = provider.createRefreshToken(7L, 1L, Role.DRIVER, AccountStatus.ACTIVE);
        String second = provider.createRefreshToken(7L, 1L, Role.DRIVER, AccountStatus.ACTIVE);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void account_without_academy_resolves_to_null_academy_id() {
        String token = provider.createAccessToken(9L, null, Role.SYSTEM_ADMIN, AccountStatus.ACTIVE);

        Claims claims = provider.parse(token);
        AuthUser principal = provider.resolveAuthUser(claims);

        assertThat(principal.academyId()).isNull();
    }

    @Test
    void 계정_상태_클레임이_왕복해도_보존된다() {
        String token = provider.createAccessToken(7L, 1L, Role.PARENT, AccountStatus.PENDING);

        Claims claims = provider.parse(token);
        AuthUser principal = provider.resolveAuthUser(claims);

        assertThat(principal.status()).isEqualTo(AccountStatus.PENDING);
    }

    /**
     * {@code status} 클레임이 없는 토큰은 조용히 통과시키면 안 된다 — 그러면 계정 상태 게이트가
     * "인증은 됐는데 상태를 모르는 요청" 을 열어 버린다(리뷰 라운드 1 Critical #1). {@link #provider}
     * 가 발급하는 토큰은 항상 status 를 담으므로 그 경로로는 재현할 수 없고, {@code parse()} 가
     * 돌려주는 {@link Claims} 도 불변이라 지울 수 없다 — status 클레임이 애초에 없는 {@link Claims}
     * 를 직접 만들어(서명·발급 경로를 우회) resolveAuthUser 에 바로 넣는다.
     *
     * <p>기대 타입은 {@link JwtException} 이지 {@link NullPointerException} 이 아니다 —
     * {@code Enum.valueOf(Class, null)} 이 던지는 실제 타입이 그대로 새 나가면
     * {@link JwtAuthenticationFilter}·{@link StompAuthChannelInterceptor} 의
     * {@code catch (JwtException | IllegalArgumentException e)} 절을 빠져나가 형식 밖 500 이
     * 된다(리뷰 라운드 2 Important #10). {@code JwtAuthenticationFilterTest} 가 그 예외를 필터가
     * 실제로 삼켜 401 로 이어지는지까지 확인한다.
     */
    @Test
    void status_클레임이_없는_토큰을_해석하면_JwtException_이다() {
        Claims claimsWithoutStatus = Jwts.claims()
                .subject("7")
                .add("academyId", 1L)
                .add("role", Role.PARENT.name())
                .add("type", "access")
                .build();

        assertThatThrownBy(() -> provider.resolveAuthUser(claimsWithoutStatus))
                .as("status 클레임이 없으면 AuthUser 를 만들 수 없어야 한다 — 만들어지면 게이트가 전면 개방된다")
                .isInstanceOf(JwtException.class);
    }

    /** {@code role} 도 {@code status} 와 같은 위험(NPE)이 있다 — 같은 방식으로 처리하는지 확인한다. */
    @Test
    void role_클레임이_없는_토큰을_해석하면_JwtException_이다() {
        Claims claimsWithoutRole = Jwts.claims()
                .subject("7")
                .add("academyId", 1L)
                .add("status", AccountStatus.ACTIVE.name())
                .add("type", "access")
                .build();

        assertThatThrownBy(() -> provider.resolveAuthUser(claimsWithoutRole))
                .as("role 클레임이 없으면 AuthUser 를 만들 수 없어야 한다")
                .isInstanceOf(JwtException.class);
    }
}
