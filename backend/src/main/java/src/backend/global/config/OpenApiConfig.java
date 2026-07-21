package src.backend.global.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;

/**
 * Swagger UI(springdoc-openapi) 문서 메타데이터.
 * JWT Bearer 인증 스킴을 등록해 Swagger UI의 "Authorize" 버튼으로 액세스 토큰을 넣으면
 * 이후 모든 요청에 {@code Authorization: Bearer <token>} 헤더가 자동으로 붙는다.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    /**
     * "00. MVP 사용 API" 태그는 여러 컨트롤러(Auth·Location·RideEvent·Routing·Notification)에 걸쳐
     * 메서드 단위로 {@code @Operation(tags = {...})}에 추가되므로, 클래스 레벨 {@code @Tag}만으로는
     * 설명(description)이 등록되지 않는다 — 여기 {@link OpenAPI#tags}에 명시적으로 등록해야 Swagger UI
     * 그룹 헤더에 설명이 뜬다.
     */
    @Bean
    public OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("School-Bus API")
                        .description("학원 통학버스 통합관리 시스템 백엔드 API. "
                                + "로그인 후 발급받은 accessToken을 우측 상단 Authorize에 입력해 인증 API를 테스트한다.")
                        .version("v0.0.1"))
                .tags(List.of(new Tag().name("00. MVP 사용 API")
                        .description("현재 구현된 MVP 5개 기능(로그인·버스 실시간위치·승하차·배차 최적화·노선배포 알림)에서 "
                                + "실제로 쓰는 API만 모은 묶음 — 새 프론트엔드가 호출할 대상. 각 API는 원래 속한 카테고리에도 그대로 나온다.")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .name(BEARER_SCHEME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
