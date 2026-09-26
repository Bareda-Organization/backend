package src.backend.global.config;

import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;

import org.springdoc.core.customizers.GlobalOpenApiCustomizer;

/**
 * 등록된 모든 DTO 스키마(요청 + 응답)에 예시값을 채운다(BR-113, Ruling 348).
 * springdoc 이 문서를 전부 조립한 뒤 호출되므로 {@code components.schemas} 가 완성된 상태로 들어온다 —
 * 요청 DTO 도 응답 DTO 도 같은 {@code components.schemas} 맵에 등록되므로 한 번의 순회로 둘 다 채운다.
 *
 * <p>필드에 <b>이미 예시가 있으면 그 필드는 손대지 않는다</b> — {@code @Schema(example = …)} 로
 * 직접 붙인 값(예: {@code LoginRequestPayload})을 덮어쓰지 않기 위해서다. 두 단계로 채운다 —
 * ① {@link SeedExampleValues#SCHEMA_PROPERTY_EXAMPLES} 에 있는 필드는 전부(스키마 안에 몇 개든)
 * 그 값으로 채운다. ② 그러고도 그 스키마에 예시가 하나도 없으면 첫 단순 타입 필드 하나를 대표값으로
 * 채운다 — {@code IMPLEMENTATION_PLAN §3.3} 의 "응답 스키마에 예시가 달린 필드가 하나 이상" 요건을
 * 111개 엔드포인트 각각을 손으로 쓰지 않고 만족시키는 방법이다.
 */
@Component
class SeedExampleSchemaCustomizer implements GlobalOpenApiCustomizer {

    /** 이 이름으로 끝나는 필드는 시각값이라 예시 대상에서 뺀다(§3.3 — 시각은 기동마다 달라진다). */
    private static final Set<String> TIME_LIKE_SUFFIXES = Set.of("_at", "_time", "date");

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
            return;
        }
        openApi.getComponents().getSchemas().forEach(this::fill);
    }

    private void fill(String schemaName, Schema<?> schema) {
        if (schema.getProperties() == null || schema.getProperties().isEmpty()) {
            return;
        }
        schema.getProperties().forEach((propertyName, property) -> {
            if (property.getExample() == null && isEligible(propertyName, property)) {
                String curated = SeedExampleValues.SCHEMA_PROPERTY_EXAMPLES.get(schemaName + "." + propertyName);
                if (curated != null) {
                    property.setExample(typed(property, curated));
                }
            }
        });
        boolean stillEmpty = schema.getProperties().values().stream()
                .noneMatch(property -> property.getExample() != null);
        if (stillEmpty) {
            schema.getProperties().entrySet().stream()
                    .filter(entry -> isEligible(entry.getKey(), entry.getValue()))
                    .findFirst()
                    .ifPresent(entry -> entry.getValue().setExample(fallback(entry.getValue())));
        }
    }

    /** 단순 타입(문자열·정수·실수·불리언)이고 $ref·배열·시각값이 아닌 필드만 예시 대상이다. */
    private boolean isEligible(String propertyName, Schema<?> property) {
        if (property.get$ref() != null || property.getItems() != null) {
            return false;
        }
        if (TIME_LIKE_SUFFIXES.stream().anyMatch(propertyName::endsWith)) {
            return false;
        }
        if ("date".equals(property.getFormat()) || "date-time".equals(property.getFormat())) {
            return false;
        }
        return Set.of("string", "integer", "number", "boolean").contains(resolvedType(property));
    }

    private String resolvedType(Schema<?> property) {
        if (property.getType() != null) {
            return property.getType();
        }
        if (property.getTypes() != null && !property.getTypes().isEmpty()) {
            return property.getTypes().iterator().next();
        }
        return "";
    }

    private Object typed(Schema<?> property, String curatedValue) {
        return "integer".equals(resolvedType(property)) ? Long.parseLong(curatedValue) : curatedValue;
    }

    /** ponytail: 대표값 — §3.3 의 "예시 존재"만 만족한다. 특정 자원과 일치시키려면 SeedExampleValues 에 추가한다. */
    private Object fallback(Schema<?> property) {
        return switch (resolvedType(property)) {
            case "integer" -> 1L;
            case "number" -> 1.0;
            case "boolean" -> true;
            default -> "example";
        };
    }
}
