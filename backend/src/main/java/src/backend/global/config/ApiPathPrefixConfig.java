package src.backend.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * API 버전 접두사 배선(Ruling 102) — {@code src.backend} 하위 {@code @RestController} 클래스에만
 * {@link #API_PREFIX} 를 붙인다. {@code server.servlet.context-path} 를 쓰지 않은 이유는 그 설정이
 * 서블릿 컨테이너 전역이라 {@code /actuator}·{@code /ws}·{@code /swagger-ui} 같은 비-API 경로까지
 * 전부 접두사를 물게 되기 때문이다 — {@code addPathPrefix} 의 {@code Predicate<Class<?>>} 로 대상을
 * {@code @RestController} 하나로만 좁힌다.
 *
 * <p>컨트롤러 소스의 {@code @RequestMapping} 리터럴 자체는 바뀌지 않는다(여전히 bare path) —
 * 접두사는 핸들러 매핑 등록 시점에 프레임워크가 덧붙인다. {@code ControllerAuthorizationConventionTest}
 * 처럼 소스 텍스트를 스캔하는 테스트가 여전히 bare path 를 보는 것은 이 때문이며 의도한 동작이다.
 */
@Configuration
public class ApiPathPrefixConfig implements WebMvcConfigurer {

    /** 모든 API 응답 경로 앞에 붙는 버전 접두사. {@code PublicEndpoints}·{@code SecurityConfig} 가 그대로 참조한다. */
    public static final String API_PREFIX = "/api/v1";

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix(API_PREFIX,
                c -> c.isAnnotationPresent(RestController.class) && c.getPackageName().startsWith("src.backend"));
    }
}
