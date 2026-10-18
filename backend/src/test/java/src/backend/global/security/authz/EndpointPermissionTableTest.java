package src.backend.global.security.authz;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * 모든 HTTP 핸들러의 "엔드포인트 → 권한" 을 {@code authz/endpoint-permissions.tsv} 표와 1:1 로 고정하는 회귀 테스트.
 *
 * <p>표의 값은 API_SPEC 각 절 「권한」 줄과 FEATURE_SPEC §6 을 옮긴 것이다. 코드의 {@code @Can} 값이
 * 바뀌거나 엔드포인트가 늘고 줄면 표를 함께 고치지 않는 한 실패한다.
 * 표의 열은 엔드포인트 · 권한 · API_SPEC 절이고, 네 번째 열(선택)은 이 권한보다 좁게 막는 서비스·access 클래스를 알리는 비검증 메모다. 권한 열: {@code can} 은 {@code @Can} 권한(여럿이면 쉼표), {@code public} 은 {@code @PublicEndpoint},
 * {@code authenticated} 는 {@code @AuthenticatedOnly}, {@code service} 는 애너테이션 없이 서비스 계층이 판정하는 핸들러.
 */
class EndpointPermissionTableTest {

    private static final String TABLE_RESOURCE = "/authz/endpoint-permissions.tsv";
    private static final String BASE_PACKAGE = "src.backend";
    /** 핸들러 수가 이 아래면 스캔이 컨트롤러를 놓친 것이다(현재 123개). */
    private static final AnnotationTypeFilter REST_CONTROLLER = new AnnotationTypeFilter(RestController.class);
    private static final int MIN_HANDLERS = 123;

    /** 코드의 모든 핸들러가 표와 같은 행·같은 권한이다. */
    @Test
    void 코드의_엔드포인트와_권한이_표와_정확히_일치한다() {
        Map<String, String> code = codeEndpoints();
        Map<String, String> table = tableRows();

        assertThat(code).hasSizeGreaterThanOrEqualTo(MIN_HANDLERS);
        assertThat(code.values().stream().map(v -> v.split(":")[0]).collect(Collectors.toSet()))
                .as("스캔이 can · public · authenticated · service 네 종류를 모두 찾아야 한다")
                .containsExactlyInAnyOrder("can", "public", "authenticated", "service");
        assertThat(differences(code, table))
                .as("엔드포인트 → 권한 표와 코드가 다르다. 표 행 형식: 'METHOD /path<TAB>권한<TAB>API_SPEC 절[<TAB>좁히는 클래스]'")
                .isEmpty();
    }

    /** 비교 함수가 행 누락 · 초과 행 · 권한 변경을 각각 잡는다. */
    @Test
    void 비교_함수는_행_누락_초과_행_권한_변경을_각각_잡는다() {
        Map<String, String> base = Map.of("GET /a", "can:X", "POST /b", "public");

        assertThat(differences(base, base)).isEmpty();
        assertThat(differences(base, Map.of("GET /a", "can:X"))).hasSize(1);
        assertThat(differences(base, Map.of("GET /a", "can:X", "POST /b", "public", "GET /c", "public"))).hasSize(1);
        assertThat(differences(base, Map.of("GET /a", "can:Y", "POST /b", "public"))).hasSize(1);
    }

    private static List<String> differences(Map<String, String> code, Map<String, String> table) {
        List<String> out = new ArrayList<>();
        code.forEach((key, value) -> {
            if (!table.containsKey(key)) {
                out.add("표에 없음: " + key + "\t" + value);
            } else if (!table.get(key).equals(value)) {
                out.add("권한 다름: " + key + " 코드=" + value + " 표=" + table.get(key));
            }
        });
        table.keySet().stream().filter(key -> !code.containsKey(key))
                .forEach(key -> out.add("코드에 없음: " + key + "\t" + table.get(key)));
        return out;
    }

    private static Map<String, String> tableRows() {
        Map<String, String> rows = new TreeMap<>();
        try (InputStream in = EndpointPermissionTableTest.class.getResourceAsStream(TABLE_RESOURCE)) {
            assertThat(in).as("표 파일 " + TABLE_RESOURCE + " 이 test resources 에 있어야 한다").isNotNull();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] cols = line.split("\t");
                assertThat(cols.length).as("표 행은 'METHOD /path<TAB>권한<TAB>API_SPEC 절[<TAB>좁히는 클래스]' 이다: " + line)
                        .isBetween(3, 4);
                assertThat(rows.put(cols[0], cols[1])).as("표에 같은 엔드포인트가 두 번 있다: " + cols[0]).isNull();
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return rows;
    }

    /** 스프링이 핸들러로 등록하는 규칙({@code @RequestMapping} 합성)을 그대로 따라 반사로 모은다. */
    private static Map<String, String> codeEndpoints() {
        // @Profile · @ConditionalOnProperty 가 붙은 개발용 컨트롤러도 핸들러이므로 조건은 보지 않는다
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(MetadataReader reader) throws IOException {
                return REST_CONTROLLER.match(reader, getMetadataReaderFactory());
            }
        };
        Map<String, String> endpoints = new TreeMap<>();
        for (var bean : scanner.findCandidateComponents(BASE_PACKAGE)) {
            Class<?> controller = ClassUtils.resolveClassName(bean.getBeanClassName(), null);
            if (!controller.getProtectionDomain().getCodeSource().getLocation().getPath().contains("/main/")) {
                continue; // 시험용 컨트롤러는 제품 핸들러가 아니다
            }
            RequestMapping onClass = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            Set<java.lang.reflect.Method> handlers = MethodIntrospector.selectMethods(controller,
                    (MethodIntrospector.MetadataLookup<RequestMapping>) m ->
                            AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class)).keySet();
            for (java.lang.reflect.Method handler : handlers) {
                RequestMapping onMethod = AnnotatedElementUtils.findMergedAnnotation(handler, RequestMapping.class);
                for (String verb : verbs(onMethod)) {
                    for (String classPath : paths(onClass)) {
                        for (String methodPath : paths(onMethod)) {
                            String key = verb + " " + (classPath + methodPath);
                            String previous = endpoints.put(key, permissionOf(handler));
                            assertThat(previous).as("같은 엔드포인트가 두 핸들러에 매핑됨: " + key).isNull();
                        }
                    }
                }
            }
        }
        return new LinkedHashMap<>(endpoints);
    }

    private static List<String> verbs(RequestMapping mapping) {
        assertThat(mapping.method()).as("HTTP 메서드가 없는 매핑은 표에 적을 수 없다").isNotEmpty();
        return Arrays.stream(mapping.method()).map(RequestMethod::name).toList();
    }

    private static List<String> paths(RequestMapping mapping) {
        if (mapping == null || mapping.path().length == 0) {
            return List.of("");
        }
        return Arrays.asList(mapping.path());
    }

    private static String permissionOf(java.lang.reflect.Method handler) {
        Can can = AnnotatedElementUtils.findMergedAnnotation(handler, Can.class);
        if (can != null) {
            return "can:" + Arrays.stream(can.value()).sorted().collect(Collectors.joining(","));
        }
        if (AnnotatedElementUtils.hasAnnotation(handler, PublicEndpoint.class)) {
            return "public";
        }
        if (AnnotatedElementUtils.hasAnnotation(handler, AuthenticatedOnly.class)) {
            return "authenticated";
        }
        return "service";
    }
}
