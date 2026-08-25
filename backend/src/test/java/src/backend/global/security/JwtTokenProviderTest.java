package src.backend.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.jsonwebtoken.Claims;
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
        String token = provider.createAccessToken(7L, 1L, Role.DRIVER, "active");

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
        String token = provider.createRefreshToken(7L, 1L, Role.DRIVER, "active");

        Claims claims = provider.parse(token);

        assertThat(provider.isRefreshToken(claims)).isTrue();
        assertThat(provider.isAccessToken(claims)).isFalse();
    }

    @Test
    void account_without_academy_resolves_to_null_academy_id() {
        String token = provider.createAccessToken(9L, null, Role.SYSTEM_ADMIN, "active");

        Claims claims = provider.parse(token);
        AuthUser principal = provider.resolveAuthUser(claims);

        assertThat(principal.academyId()).isNull();
    }

    @Test
    void 계정_상태_클레임이_왕복해도_보존된다() {
        String token = provider.createAccessToken(7L, 1L, "parent", "pending");

        Claims claims = provider.parse(token);
        AuthUser principal = provider.resolveAuthUser(claims);

        assertThat(principal.status()).isEqualTo("pending");
    }
}
