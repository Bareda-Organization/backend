package src.backend.global.security;

import java.util.Arrays;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import src.backend.global.request.RequestIdFilter;

/**
 * {@code /api/**} 의 CORS 설정 — {@code app.cors.allowed-origins} 에 등록된 출처만 자격 증명과 함께 허용한다. {@link SecurityConfig} 가 이 값으로 빈을 만든다.
 */
final class ApiCorsConfiguration {

    private ApiCorsConfiguration() {
    }

    /** 쉼표로 구분한 허용 출처 목록으로 {@code /api/**} 용 CORS 설정 원본을 만든다. */
    static CorsConfigurationSource source(String allowedOrigins) {
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toList();

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        // API_SPEC §1.2.1 — refresh 토큰이 웹에서 쿠키로만 오간다. 이 값이 없으면 브라우저가
        // `credentials: include` 요청을 통째로 거부해 웹 로그인 자체가 성립하지 않는다.
        // 허용 출처가 `*` 가 아니라 명시 목록이라 이 조합이 성립한다.
        configuration.setAllowCredentials(true);
        // 브라우저는 노출 목록에 없는 응답 헤더를 스크립트에 숨긴다 — 웹이 요청 추적 식별자와 503 의 재시도 대기(§1.3)를 읽게 한다
        configuration.setExposedHeaders(List.of(RequestIdFilter.HEADER, HttpHeaders.RETRY_AFTER));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
