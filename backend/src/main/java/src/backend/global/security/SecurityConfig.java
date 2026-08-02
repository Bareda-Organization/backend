package src.backend.global.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
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

import src.backend.global.security.authz.RolePermissions;

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

    /**
     * 역할 → 권한(permission) 부여표를 인가 평가에 연결한다.
     * {@code @EnableMethodSecurity} 가 이 빈을 자동으로 집어가(@Autowired(required=false))
     * {@code hasAuthority('student:manage')} 를 평가할 때 주체의 ROLE_* 를 권한으로 확장해 준다.
     *
     * 별도 @Configuration 으로 빼지 않고 여기 두는 이유: @WebMvcTest 슬라이스 6개가
     * {@code @Import(SecurityConfig.class)} 로 이 클래스만 들여온다. 다른 클래스로 옮기면
     * 슬라이스마다 @Import 를 추가해야 하고, 하나라도 빠뜨리면 그 슬라이스에서만
     * 권한 확장이 안 돼 허용 경로가 조용히 403 이 된다.
     *
     * <p><b>이 클래스에서 유일하게 {@code static} 인 @Bean 이다 — 지우지 말 것.</b>
     * 메서드 시큐리티 인프라는 이 빈을 BeanPostProcessor 단계에서 참조하는데, 인스턴스
     * 메서드로 두면 그때 {@code SecurityConfig} 자체가 먼저 만들어져야 하고, 그러면
     * 생성자 의존인 {@code JwtAuthenticationFilter} 까지 후처리가 끝나기 전에 끌려 나온다.
     * {@code static} 이면 설정 클래스를 인스턴스화하지 않고 빈만 만들 수 있어 그 사슬이 끊긴다
     * (스프링 시큐리티 레퍼런스의 RoleHierarchy 예제도 전부 {@code static} 이다).
     * 이 메서드가 인스턴스 상태를 안 쓰기 때문에 가능한 것이고, 옆의 다른 @Bean 들은
     * {@code jwtAuthenticationFilter}·{@code allowedOrigins} 를 쓰므로 static 이 될 수 없다.
     */
    @Bean
    static RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.fromHierarchy(RolePermissions.HIERARCHY);
    }
}
