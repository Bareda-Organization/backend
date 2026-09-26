package src.backend.global.config;

import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;

import org.springdoc.core.customizers.OperationCustomizer;

/**
 * 경로·쿼리 파라미터에 시드 기반 예시를 채운다(BR-113, Ruling 348) — 요청·응답 DTO 는
 * {@link SeedExampleSchemaCustomizer} 가 맡고, 이 클래스는 파라미터(스키마에 안 실리는 값)만 맡는다.
 *
 * <p>이미 {@code @Parameter(example = …)} 로 직접 붙인 값은 손대지 않는다. "id" 처럼 컨트롤러마다
 * 다른 자원을 가리키는 이름은 {@link HandlerMethod#getBeanType()} 으로 컨트롤러를 가려
 * {@link SeedExampleValues#PARAMETER_EXAMPLES} 의 {@code <컨트롤러>#<파라미터>} 키를 먼저 찾는다.
 */
@Component
class SeedExampleOperationCustomizer implements OperationCustomizer {

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        if (operation.getParameters() == null) {
            return operation;
        }
        String controllerKey = handlerMethod.getBeanType().getSimpleName() + "#";
        for (Parameter parameter : operation.getParameters()) {
            if (parameter.getExample() != null
                    || (parameter.getSchema() != null && parameter.getSchema().getExample() != null)) {
                continue; // 이미 값이 있다 — @Parameter 로 직접 붙였거나 앞선 처리에서 채워졌다
            }
            String curated = SeedExampleValues.PARAMETER_EXAMPLES.get(controllerKey + parameter.getName());
            if (curated == null) {
                curated = SeedExampleValues.PARAMETER_EXAMPLES.get(parameter.getName());
            }
            Object example = curated != null ? typed(parameter.getSchema(), curated) : fallback(parameter);
            if (example != null) {
                parameter.setExample(example);
            }
        }
        return operation;
    }

    private Object typed(Schema<?> schema, String value) {
        return schema != null && "integer".equals(schema.getType()) ? Long.parseLong(value) : value;
    }

    /**
     * ponytail: 표에 없는 파라미터의 대표값 — §3.3 의 "예시 존재"만 만족한다.
     * {@code format=date} 류는 swagger-ui 자체 추정이 더 나을 수 있어 손대지 않고 {@code null} 반환.
     */
    private Object fallback(Parameter parameter) {
        Schema<?> schema = parameter.getSchema();
        if (schema == null) {
            return "example";
        }
        String format = schema.getFormat();
        if ("date".equals(format) || "date-time".equals(format)) {
            return null;
        }
        return switch (String.valueOf(schema.getType())) {
            case "integer" -> 1L;
            case "number" -> 1.0;
            case "boolean" -> true;
            default -> "example";
        };
    }
}
