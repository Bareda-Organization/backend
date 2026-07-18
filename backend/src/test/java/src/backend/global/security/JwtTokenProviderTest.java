package src.backend.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.jsonwebtoken.Claims;

/**
 * JWT 발급 → 검증 왕복 단위 테스트(스프링 컨텍스트 없이).
 * secret·유효기간을 직접 주입해 access/refresh 구분과 클레임 복원을 확인한다.
 */
class JwtTokenProviderTest {

    private final JwtTokenProvider provider = new JwtTokenProvider(
            "test-secret-key-for-jwt-that-is-at-least-32-bytes-long!!", 900, 1209600);

    @Test
    void access_token_round_trip() {
        String token = provider.createAccessToken(7L, "driver@school.com", List.of("1:DRIVER"));

        Claims claims = provider.parse(token);

        assertThat(claims.getSubject()).isEqualTo("7");
        assertThat(claims.get("email", String.class)).isEqualTo("driver@school.com");
        assertThat(provider.isAccessToken(claims)).isTrue();
        assertThat(provider.isRefreshToken(claims)).isFalse();

        AuthUser principal = AuthUser.fromEncoded(
                Long.valueOf(claims.getSubject()),
                claims.get("email", String.class),
                claims.get("memberships", List.class));
        assertThat(principal.belongsToTenant(1L)).isTrue();
        assertThat(principal.authorities()).extracting("authority").containsExactly("ROLE_DRIVER");
    }

    @Test
    void refresh_token_is_flagged_as_refresh() {
        String token = provider.createRefreshToken(7L, "driver@school.com", List.of("1:DRIVER"));

        Claims claims = provider.parse(token);

        assertThat(provider.isRefreshToken(claims)).isTrue();
        assertThat(provider.isAccessToken(claims)).isFalse();
    }

    @Test
    void platform_admin_membership_has_null_tenant() {
        String token = provider.createAccessToken(9L, "platform@school.com", List.of(":PLATFORM_ADMIN"));

        Claims claims = provider.parse(token);
        AuthUser principal = AuthUser.fromEncoded(9L, "platform@school.com",
                claims.get("memberships", List.class));

        assertThat(principal.isPlatformAdmin()).isTrue();
        assertThat(principal.tenantIds()).isEmpty();
    }
}
