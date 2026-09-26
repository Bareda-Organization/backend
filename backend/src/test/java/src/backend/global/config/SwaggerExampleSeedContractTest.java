package src.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import src.backend.global.common.SeedFixtures;

/**
 * 겹②(문서 예시 대조, {@code IMPLEMENTATION_PLAN.md §3.3} · BR-113 · Ruling 348).
 * {@code /v3/api-docs} 를 받아 {@link SeedExampleValues} 가 채워 넣은 예시값이 실제 문서에
 * 그대로 실렸는지 검증한다 — 값이 실재하는지는 겹①({@code SeedFixturesContractTest})이 이미 본다.
 *
 * <p>경로·쿼리 파라미터 중 컨트롤러로 갈라야 하는 것("id")은 문서만으로는 어느 컨트롤러의
 * 오퍼레이션인지 되짚을 수 없어 이 시험의 대상 밖이다({@code FIX-T.md} §2) — 이름만으로 갈리는
 * 파라미터({@code runId} · {@code academyId} · {@code academy_id})만 검증한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SwaggerExampleSeedContractTest {

    @LocalServerPort
    private int port;

    // springdoc 도 자기 매핑 빈을 등록하므로 이름으로 고른다(OpenApiCoverageTest 와 같은 근거).
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    private final TestRestTemplate restTemplate = new TestRestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private JsonNode apiDocs() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity("http://localhost:" + port + "/v3/api-docs", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody());
    }

    /** {@link SeedExampleValues#SCHEMA_PROPERTY_EXAMPLES} 의 스키마·필드 예시가 문서에 그대로 실린다. */
    @Test
    void 스키마_필드_예시가_시드_상수와_일치한다() throws Exception {
        JsonNode schemas = apiDocs().path("components").path("schemas");
        List<String> mismatches = new ArrayList<>();
        SeedExampleValues.SCHEMA_PROPERTY_EXAMPLES.forEach((key, expected) -> {
            int dot = key.indexOf('.');
            String schemaName = key.substring(0, dot);
            String propertyName = key.substring(dot + 1);
            JsonNode example = schemas.path(schemaName).path("properties").path(propertyName).path("example");
            if (example.isMissingNode() || !expected.equals(example.asText())) {
                mismatches.add("%s → 기대 %s, 실제 %s".formatted(key, expected, example.asText("(없음)")));
            }
        });
        assertThat(mismatches).as("스키마 예시가 SeedExampleValues 와 어긋난 자리").isEmpty();
    }

    /** 이름만으로 자원이 갈리는 경로·쿼리 파라미터("runId" 등)의 예시가 문서에 그대로 실린다. */
    @Test
    void 이름으로_갈리는_파라미터_예시가_시드_상수와_일치한다() throws Exception {
        Map<String, String> bareKeyed = SeedExampleValues.PARAMETER_EXAMPLES.entrySet().stream()
                .filter(entry -> !entry.getKey().contains("#"))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        JsonNode paths = apiDocs().path("paths");
        List<String> mismatches = new ArrayList<>();
        paths.fieldNames().forEachRemaining(path -> {
            JsonNode methods = paths.path(path);
            methods.fieldNames().forEachRemaining(method -> {
                JsonNode parameters = methods.path(method).path("parameters");
                if (!parameters.isArray()) {
                    return;
                }
                for (JsonNode parameter : parameters) {
                    String name = parameter.path("name").asText();
                    String expected = bareKeyed.get(name);
                    if (expected == null) {
                        continue;
                    }
                    String actual = parameter.hasNonNull("example")
                            ? parameter.path("example").asText()
                            : parameter.path("schema").path("example").asText(null);
                    if (!expected.equals(actual)) {
                        mismatches.add("%s %s [%s] → 기대 %s, 실제 %s"
                                .formatted(method.toUpperCase(), path, name, expected, actual));
                    }
                }
            });
        });
        assertThat(mismatches).as("경로·쿼리 파라미터 예시가 SeedExampleValues 와 어긋난 자리").isEmpty();
    }

    /**
     * 겹②의 진짜 안전망 — 위 두 시험은 문서가 {@link SeedExampleValues} 와 같은지만 보므로, 그 맵
     * 자체에 리터럴이 박혀도(예: {@code Map.entry("run_id", "42")}) 잡지 못한다. 이 시험은
     * {@link SeedFixtures} 를 리플렉션으로 훑어 "실재하는 값 사전"을 만들고, {@link SeedExampleValues}
     * 의 모든 값이 그 사전 안에 있는지 본다 — 리터럴을 새로 적으면 사전에 없어 여기서 걸린다.
     */
    @Test
    void 예시_사전의_값은_전부_SeedFixtures_상수다() throws Exception {
        Set<String> knownValues = new HashSet<>();
        for (Field field : SeedFixtures.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                knownValues.add((String) field.get(null));
            }
        }

        List<String> unknown = new ArrayList<>();
        SeedExampleValues.SCHEMA_PROPERTY_EXAMPLES.forEach((key, value) -> {
            if (!knownValues.contains(value)) {
                unknown.add("SCHEMA_PROPERTY_EXAMPLES[%s] = %s".formatted(key, value));
            }
        });
        SeedExampleValues.PARAMETER_EXAMPLES.forEach((key, value) -> {
            if (!knownValues.contains(value)) {
                unknown.add("PARAMETER_EXAMPLES[%s] = %s".formatted(key, value));
            }
        });

        assertThat(unknown).as("SeedFixtures 상수가 아닌 리터럴 값").isEmpty();
    }

    /**
     * 경로 변수 "id" 가 사전 없이 대표값 1 로 떨어지는 오퍼레이션이 0 인지 검사한다(BR-113 잔여, Ruling 348).
     * 문서(JSON)만 보면 "우연히 진짜 1 인 예시"와 "사전이 없어 떨어진 대표값 1"을 구분할 수 없어서
     * ({@code FIX-T.md} §2), {@link SeedExampleOperationCustomizer#customize} 가 실제로 참조하는 키
     * (컨트롤러 클래스명 + "#" + 파라미터명)를 그대로 재현해 사전 부재 자체를 판정한다.
     */
    @Test
    void 경로_변수_id_는_예시_사전에_전부_등록돼_있다() {
        List<String> missing = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            HandlerMethod handlerMethod = entry.getValue();
            if (!isProductionController(handlerMethod.getBeanType())) {
                continue;
            }
            for (MethodParameter parameter : handlerMethod.getMethodParameters()) {
                PathVariable pathVariable = parameter.getParameterAnnotation(PathVariable.class);
                if (pathVariable == null) {
                    continue;
                }
                String name = pathVariableName(pathVariable, parameter);
                if (!"id".equals(name)) {
                    continue; // 이름으로 자원이 갈리는 파라미터(runId 등)는 위 시험이 이미 본다
                }
                String key = handlerMethod.getBeanType().getSimpleName() + "#" + name;
                if (!SeedExampleValues.PARAMETER_EXAMPLES.containsKey(key)) {
                    missing.add(key);
                }
            }
        }
        assertThat(missing).as("사전에 없어 대표값 1로 떨어지는 id 경로 변수").isEmpty();
    }

    /** {@code @PathVariable} 에 이름을 안 적은 경우 컴파일러의 {@code -parameters} 산출물에서 읽는다. */
    private static String pathVariableName(PathVariable pathVariable, MethodParameter parameter) {
        if (!pathVariable.value().isBlank()) {
            return pathVariable.value();
        }
        Parameter reflected = parameter.getMethod().getParameters()[parameter.getParameterIndex()];
        return reflected.getName();
    }

    /** {@code OpenApiCoverageTest.isProductionController} 와 같은 판정 — 시험 전용 컨트롤러는 사양 대상이 아니다. */
    private static boolean isProductionController(Class<?> beanType) {
        if (!beanType.getPackageName().startsWith("src.backend")) {
            return false;
        }
        var codeSource = beanType.getProtectionDomain().getCodeSource();
        return codeSource != null && codeSource.getLocation().getPath().contains("/classes/java/main/");
    }
}
