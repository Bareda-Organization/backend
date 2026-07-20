package src.backend.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * Swagger UI(springdoc-openapi) 문서 메타데이터.
 * JWT Bearer 인증 스킴을 등록해 Swagger UI의 "Authorize" 버튼으로 액세스 토큰을 넣으면
 * 이후 모든 요청에 {@code Authorization: Bearer <token>} 헤더가 자동으로 붙는다.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("School-Bus API")
                        .description("학원 통학버스 통합관리 시스템 백엔드 API. "
                                + "로그인 후 발급받은 accessToken을 우측 상단 Authorize에 입력해 인증 API를 테스트한다.")
                        .version("v0.0.1"))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .name(BEARER_SCHEME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
