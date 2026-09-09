package src.backend.global.config;

import java.util.Map;
import java.util.stream.Stream;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;

import src.backend.global.response.ErrorResponse;
import src.backend.global.security.PublicEndpoints;

/**
 * 모든 엔드포인트에 공통 에러 응답을 실어 주는 문서 보정(API_SPEC §1.11).
 * 공통 항목은 정의상 104개 핸들러 전부에 해당하므로 핸들러마다 애너테이션으로 적으면 같은 문장이
 * 104벌로 늘고, 규약이 바뀔 때 한 곳만 고쳐지고 나머지가 낡는다 — 사양이 "개별 엔드포인트의 에러 줄에는
 * 반복 기재 부재" 라고 정한 것과 같은 이유다. 그래서 한 곳에서 붙인다.
 *
 * <p>비인증 허용 경로({@link PublicEndpoints})는 토큰 자체가 없으므로 401·403 을 달지 않는다 —
 * 사양 §1.11 의 "비인증 경로에는 401·403 미적용" 을 그대로 옮긴 것이다.
 */
@Component
public class CommonErrorResponsesCustomizer implements OpenApiCustomizer {

    private static final String SCHEMA_NAME = "ErrorResponse";

    /** 실패 응답 봉투는 전 경로가 같다(§1.10) — 스키마를 한 번만 등록하고 응답들이 참조한다. */
    private static final String SCHEMA_REF = "#/components/schemas/" + SCHEMA_NAME;

    private static final String UNAUTHORIZED = "TOKEN_EXPIRED — access 토큰 만료·로그아웃·차단으로 무효화 (§1.2)";
    private static final String FORBIDDEN =
            "AUTH_PENDING · AUTH_REJECTED · AUTH_ACCOUNT_BLOCKED · FORBIDDEN · ACADEMY_SCOPE_VIOLATION (§1.4 · §1.5)";
    private static final String UNPROCESSABLE = "VALIDATION_FAILED — 필수 필드 누락 · 형식 위반";

    @Override
    public void customise(OpenAPI openApi) {
        registerErrorSchema(openApi);
        openApi.getPaths().forEach((path, item) -> {
            boolean open = isPublic(path);
            item.readOperations().forEach(operation -> {
                if (!open) {
                    merge(operation, "401", UNAUTHORIZED);
                    merge(operation, "403", FORBIDDEN);
                }
                merge(operation, "422", UNPROCESSABLE);
            });
            // 엔드포인트 고유 항목은 공통 항목 뒤에 잇는다 — 같은 상태 코드를 쓰는 자리가 실제로 있다
            // (예 §4.6 승하차 처리의 403 ESCORT_ONLY 는 공통 403 과 나란히 뜬다).
            item.readOperationsMap().forEach((httpMethod, operation) -> {
                Map<String, String> byStatus = EndpointErrorResponses.BY_ENDPOINT
                        .get(httpMethod.name() + " " + path.replaceFirst(ApiPathPrefixConfig.API_PREFIX, ""));
                if (byStatus != null) {
                    byStatus.forEach((status, codes) -> merge(operation, status, codes));
                }
            });
        });
    }

    /** 봉투 스키마를 components 에 등록한다. 애너테이션으로 참조되지 않는 타입이라 직접 넣어야 한다. */
    private void registerErrorSchema(OpenAPI openApi) {
        if (openApi.getComponents() == null || openApi.getComponents().getSchemas() != null
                && openApi.getComponents().getSchemas().containsKey(SCHEMA_NAME)) {
            return;
        }
        ModelConverters.getInstance().readAll(ErrorResponse.class)
                .forEach((name, schema) -> openApi.getComponents().addSchemas(name, schema));
    }

    private boolean isPublic(String path) {
        return Stream.concat(PublicEndpoints.GET_ENDPOINTS.stream(), PublicEndpoints.POST_ENDPOINTS.stream())
                .anyMatch(open -> path.equals(ApiPathPrefixConfig.API_PREFIX + open));
    }

    /**
     * 이미 그 상태 코드를 문서화한 핸들러면 설명을 잇는다.
     * 덮어쓰면 {@code @ApiResponse} 로 적어 둔 엔드포인트 고유 코드가 사라지고, 건너뛰면 공통 항목이
     * 그 엔드포인트에서만 빠진다 — 어느 쪽도 사양과 어긋나므로 두 문장을 함께 남긴다.
     */
    private void merge(Operation operation, String status, String description) {
        ApiResponses responses = operation.getResponses();
        if (responses == null) {
            responses = new ApiResponses();
            operation.setResponses(responses);
        }
        ApiResponse existing = responses.get(status);
        if (existing == null) {
            responses.addApiResponse(status, new ApiResponse()
                    .description(description)
                    .content(errorContent()));
            return;
        }
        String current = existing.getDescription();
        if (current == null || current.isBlank()) {
            existing.setDescription(description);
        } else if (!current.contains(description)) {
            existing.setDescription(current + " · " + description);
        }
        if (existing.getContent() == null) {
            existing.setContent(errorContent());
        }
    }

    private Content errorContent() {
        return new Content().addMediaType("application/json",
                new MediaType().schema(new Schema<>().$ref(SCHEMA_REF)));
    }
}
