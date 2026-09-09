package src.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Swagger 문서가 프로덕션 API 전체를 담는지 검사하는 전수 대조.
 * springdoc 은 애너테이션 없이도 오퍼레이션을 만들어 주므로 "문서가 생성됐다" 와 "모든 API 가 실렸다" 는
 * 다른 사실이다 — 컨트롤러를 새로 만들면서 경로 접두사·HTTP 메서드를 어긋나게 적어도 컴파일과 기존
 * 시험은 통과하고 Swagger 목록에서만 조용히 빠진다. 실서버(RANDOM_PORT)가 낸 {@code /v3/api-docs} 를
 * {@link RequestMappingHandlerMapping} 이 실제로 등록한 핸들러와 맞대어 그 누락을 잡는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiCoverageTest {

    /**
     * 사양이 정한 태그 5종({@code docs/API_SPEC.md} §2~§6). 이 밖의 태그는 springdoc 이 클래스명으로 만든 것이다.
     * 문자열을 여기 다시 적지 않고 {@link ApiTags} 를 가리킨다 — 두 벌로 두면 상수를 고쳤을 때
     * 이 시험만 옛 이름을 검사해 통과하고, 정작 화면에서 그룹이 갈라진 것은 잡히지 않는다.
     */
    private static final Set<String> SPEC_TAGS = Set.of(
            ApiTags.AUTH, ApiTags.PARENT_STUDENT, ApiTags.MANAGER, ApiTags.STAFF, ApiTags.ADMIN);

    @LocalServerPort
    private int port;

    // springdoc 도 자기 매핑 빈을 등록하므로 이름으로 고른다 — 타입만으로는 후보가 둘이다.
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    private final TestRestTemplate restTemplate = new TestRestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 사양 대상인 프로덕션 컨트롤러인지 가른다.
     * 패키지 이름으로 가르면 시험 전용 컨트롤러({@code AcademyScopeTestController} 등)가 같은
     * {@code src.backend} 아래 있어 섞인다. 클래스가 실제로 로드된 위치가 {@code classes/java/main}
     * 인지를 보면 소스 세트 자체로 갈리므로 새 시험 컨트롤러가 생겨도 규칙을 고칠 필요가 없다.
     */
    private boolean isProductionController(Class<?> beanType) {
        if (!beanType.getPackageName().startsWith("src.backend")) {
            return false; // springdoc · actuator · 오류 처리 컨트롤러는 사양 대상이 아니다
        }
        var codeSource = beanType.getProtectionDomain().getCodeSource();
        return codeSource != null && codeSource.getLocation().getPath().contains("/classes/java/main/");
    }

    /** 프로덕션 컨트롤러가 실제로 등록한 매핑 — {@code "GET /api/staff/runs"} 형태. */
    private Set<String> registeredEndpoints() {
        Set<String> result = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            if (!isProductionController(entry.getValue().getBeanType())) {
                continue;
            }
            RequestMappingInfo info = entry.getKey();
            Set<String> patterns = new LinkedHashSet<>();
            if (info.getPathPatternsCondition() != null) {
                info.getPathPatternsCondition().getPatternValues().forEach(patterns::add);
            }
            for (String pattern : patterns) {
                info.getMethodsCondition().getMethods()
                        .forEach(method -> result.add(method.name() + " " + pattern));
            }
        }
        return result;
    }

    private JsonNode apiDocs() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity("http://localhost:" + port + "/v3/api-docs", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody());
    }

    /**
     * 문서에 실린 오퍼레이션 — {@code "GET /api/staff/runs"} 형태.
     * springdoc 은 시험 소스의 컨트롤러까지 문서에 싣는다. 그 경로는 배포물에 없으므로 걷어낸다.
     */
    private Set<String> documentedEndpoints(JsonNode apiDocs) {
        Set<String> productionPaths = new TreeSet<>();
        for (String endpoint : registeredEndpoints()) {
            productionPaths.add(endpoint.substring(endpoint.indexOf(' ') + 1));
        }
        Set<String> result = new TreeSet<>();
        JsonNode paths = apiDocs.path("paths");
        paths.fieldNames().forEachRemaining(path -> {
            if (!productionPaths.contains(path)) {
                return;
            }
            paths.path(path).fieldNames().forEachRemaining(method ->
                    result.add(method.toUpperCase() + " " + path));
        });
        return result;
    }

    /** 프로덕션 경로만 남긴 {@code paths} 순회 — 시험 전용 컨트롤러의 오퍼레이션을 판정에서 뺀다. */
    private void forEachProductionOperation(JsonNode apiDocs, java.util.function.BiConsumer<String, JsonNode> visitor) {
        Set<String> productionPaths = new TreeSet<>();
        for (String endpoint : registeredEndpoints()) {
            productionPaths.add(endpoint.substring(endpoint.indexOf(' ') + 1));
        }
        JsonNode paths = apiDocs.path("paths");
        paths.fieldNames().forEachRemaining(path -> {
            if (!productionPaths.contains(path)) {
                return;
            }
            paths.path(path).fieldNames().forEachRemaining(method ->
                    visitor.accept(method.toUpperCase() + " " + path, paths.path(path).path(method)));
        });
    }

    /**
     * 목표 1 — 등록된 핸들러가 문서에서 하나도 빠지지 않는다.
     * 개수 비교가 아니라 항목 대조다. 개수만 맞추면 한 건이 빠지고 다른 한 건이 잘못 실린 상태를 통과시킨다.
     */
    @Test
    void everyRegisteredEndpointAppearsInApiDocs() throws Exception {
        Set<String> registered = registeredEndpoints();
        Set<String> documented = documentedEndpoints(apiDocs());

        Set<String> missing = new TreeSet<>(registered);
        missing.removeAll(documented);

        assertThat(registered).as("프로덕션 핸들러가 하나도 등록되지 않았다면 대조 자체가 무의미하다").isNotEmpty();
        assertThat(missing)
                .as("Swagger 문서에서 빠진 엔드포인트 — 등록 %d건 · 문서 %d건".formatted(registered.size(), documented.size()))
                .isEmpty();
    }

    /** 목표 2 — 오퍼레이션마다 사람이 읽을 설명이 붙어 있다. {@code @Operation(summary = ...)} 이 없으면 빈다. */
    @Test
    void everyOperationHasSummary() throws Exception {
        List<String> withoutSummary = new ArrayList<>();
        int[] total = {0};
        forEachProductionOperation(apiDocs(), (endpoint, operation) -> {
            total[0]++;
            if (operation.path("summary").asText("").isBlank()) {
                withoutSummary.add(endpoint);
            }
        });
        withoutSummary.sort(Comparator.naturalOrder());

        assertThat(withoutSummary)
                .as("summary 가 없는 오퍼레이션 %d건 / 전체 %d건".formatted(withoutSummary.size(), total[0]))
                .isEmpty();
    }

    /** 목표 3 — 오퍼레이션이 사양 태그 5종에만 묶인다. 태그를 안 달면 springdoc 이 클래스명으로 만들어 붙인다. */
    @Test
    void everyOperationIsGroupedUnderSpecTags() throws Exception {
        Set<String> foreignTags = new TreeSet<>();
        List<String> untagged = new ArrayList<>();
        int[] total = {0};
        forEachProductionOperation(apiDocs(), (endpoint, operation) -> {
            total[0]++;
            JsonNode tags = operation.path("tags");
            if (!tags.isArray() || tags.isEmpty()) {
                untagged.add(endpoint);
                return;
            }
            tags.forEach(tag -> {
                if (!SPEC_TAGS.contains(tag.asText())) {
                    foreignTags.add(tag.asText());
                }
            });
        });
        untagged.sort(Comparator.naturalOrder());

        assertThat(untagged).as("태그가 없는 오퍼레이션 %d건 / 전체 %d건".formatted(untagged.size(), total[0])).isEmpty();
        assertThat(foreignTags)
                .as("사양에 없는 태그 %d종 — springdoc 이 클래스명으로 만든 것".formatted(foreignTags.size()))
                .isEmpty();
    }
}
