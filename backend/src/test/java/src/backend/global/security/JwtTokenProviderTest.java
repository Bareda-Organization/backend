package src.backend.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import io.jsonwebtoken.Claims;
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
     */
    @Test
    void status_클레임이_없는_토큰은_해석에_실패한다() {
        Claims claimsWithoutStatus = Jwts.claims()
                .subject("7")
                .add("academyId", 1L)
                .add("role", Role.PARENT.name())
                .add("type", "access")
                .build();

        assertThatThrownBy(() -> provider.resolveAuthUser(claimsWithoutStatus))
                .as("status 클레임이 없으면 AuthUser 를 만들 수 없어야 한다 — 만들어지면 게이트가 전면 개방된다")
                .isInstanceOf(NullPointerException.class);
    }
}
