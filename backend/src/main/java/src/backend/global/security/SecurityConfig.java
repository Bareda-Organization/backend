package src.backend.global.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * 보안 설정.
 * 토큰 기반이라 세션을 만들지 않고(STATELESS), CSRF 를 끈다.
 * JwtAuthenticationFilter 를 표준 인증 필터 앞에 끼워 매 요청 토큰을 검증한다.
 * 인증/회원가입(/api/auth/**)과 헬스체크·Swagger UI만 공개, 그 외는 인증 필요.
 * 세밀한 역할 인가는 각 컨트롤러의 @PreAuthorize 로 처리한다(@EnableMethodSecurity).
 *
 * /ws/** 는 예외 — WebSocket 업그레이드(HTTP 핸드셰이크) 자체엔 아직 토큰이 없고(네이티브
 * WebSocket 클라이언트는 임의 헤더를 못 붙이는 경우가 많음), 인증은 STOMP CONNECT 프레임에서
 * {@code StompAuthChannelInterceptor}가 대신 검증한다.
 *
 * /api/** 는 {@code app.cors.allowed-origins}(application.yml)에 등록된 출처만 CORS 허용 —
 * 브라우저 기반 프론트엔드 개발 서버(포트가 다름)가 붙을 때 프리플라이트가 막히는 것을 막기 위함.
 * /ws/** 는 이미 {@code WebSocketConfig}에서 자체 origin 패턴을 열어두므로 이 설정과 무관하다.
 */
@Configuration
@EnableWebSecurity   // HttpSecurity 등 보안 인프라 빈 등록(앱·슬라이스 테스트 공통)
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Value("${app.cors.allowed-origins:}")
    private String allowedOrigins;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/ws/**").permitAll()
                        // API 테스트용 Swagger UI(springdoc) — 문서·UI 자체는 공개, 보호 API 호출은 Authorize 로 넣은 토큰이 검증
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        .anyRequest().authenticated())
                // 인증 안 된 요청은 403 대신 401 로 응답(토큰 필요함을 명확히)
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toList();

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
