package src.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

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
}
