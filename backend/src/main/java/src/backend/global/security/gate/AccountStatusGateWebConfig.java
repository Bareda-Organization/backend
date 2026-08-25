package src.backend.global.security.gate;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * {@link AccountStatusGateInterceptor}를 전체 요청 경로에 등록한다.
 * {@code SecurityConfig}(필터 체인)와는 별도 계층 — 인터셉터는 핸들러가 정해진 뒤(HandlerMethod
 * 애너테이션 조회가 가능한 시점)에 실행돼야 해 필터로는 표현할 수 없다.
 */
@Configuration
public class AccountStatusGateWebConfig implements WebMvcConfigurer {

    private final AccountStatusGateInterceptor accountStatusGateInterceptor;

    public AccountStatusGateWebConfig(AccountStatusGateInterceptor accountStatusGateInterceptor) {
        this.accountStatusGateInterceptor = accountStatusGateInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(accountStatusGateInterceptor);
    }
}
