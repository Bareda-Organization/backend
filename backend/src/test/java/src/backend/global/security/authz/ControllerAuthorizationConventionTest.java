package src.backend.global.security.authz;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * {@code @RestController} 빈의 public 매핑 메서드가 인가를 권한 애너테이션으로만 표현하는지
 * 소스 수준에서 고정하는 회귀 방지 테스트.
 *
 * <p>규칙을 문서와 주석에만 적어 두면 다음 사람이 습관대로
 * {@code @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")} 한 줄을 새 엔드포인트에 붙여도
 * 아무것도 막지 않는다. 그 한 줄이 늘어날 때마다 "새 역할 추가 = 부여표 1파일" 이라는 이 구조의 이점이
 * 조용히 깎이므로, 되돌아가는 것 자체를 실패로 만든다.
 *
 * <p>인가 애너테이션 이름을 하드코딩하지 않는다 — {@code src.backend.global.security.authz}
 * 패키지에 실존하는 애너테이션 파일 목록을 매 실행 스캔해 대조한다. 이름이 바뀌거나(Phase 2)
 * 종수가 늘어도 이 테스트를 고치지 않아도 된다.
 *
 * <p>런타임 인가 동작이 아니라 <b>소스 텍스트</b>를 검사한다. 실제 200/403 은 컨트롤러별
 * 슬라이스 테스트가 담당한다.
 */
class ControllerAuthorizationConventionTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java/src/backend");
    private static final Path AUTHZ_ANNOTATION_ROOT = SOURCE_ROOT.resolve("global/security/authz");

    /**
     * {@code 파일명#메서드명} 형태로 적는 예외 목록 — 여기 없는 매핑은 클래스 레벨이나 메서드 레벨에
     * 인가 애너테이션이 반드시 있어야 한다.
     *
     * <p><b>이 목록에 항목을 추가할 때는 서비스 계층에서 범위 검증이 실제로 이뤄지는지 확인하고,
     * 그 근거를 여기에 적는다.</b> 이름만 적고 근거가 없으면, 같은 파일에 인가 없는 매핑이 하나 더
     * 늘어도 이 목록이 통째로 가려 버려 회귀를 못 잡는다.
     */
    private static final List<String> METHOD_LEVEL_EXEMPT = List.of();

    /** 인가 자체가 아직 없는 permitAll 경로(로그인·회원가입·토큰 재발급 등)를 담는 파일명 목록. */
    private static final List<String> EXEMPT_FILES = List.of();

    /** 메서드 위에 붙을 수 있는 매핑 애너테이션 — 이 중 하나가 있어야 "엔드포인트 메서드"로 센다. */
    private static final List<String> MAPPING_ANNOTATIONS = List.of(
            "@GetMapping", "@PostMapping", "@PutMapping", "@PatchMapping", "@DeleteMapping",
            "@RequestMapping", "@MessageMapping");

    private static final Map<String, String> HTTP_METHOD_BY_MAPPING_ANNOTATION = Map.of(
            "@GetMapping", "GET", "@PostMapping", "POST", "@PutMapping", "PUT",
            "@PatchMapping", "PATCH", "@DeleteMapping", "DELETE");

    /**
     * 조율자 Ruling 77 — {@code @PublicEndpoint} 가 붙은 엔드포인트의 <b>허용 목록</b>.
     * API_SPEC §2.1·2.2·2.5·2.6·2.9(학원검색·회원가입·로그인·토큰재발급·아이디비밀번호복구)에서
     * 그대로 옮겼다 — 컨트롤러·메서드 이름이 아니라 "HTTP 메서드 + 경로"로 고정한 이유는, 이 값이
     * 사양에서 바로 나와 Task 3·4 가 어떤 클래스·메서드 이름을 고르든 흔들리지 않기 때문이다.
     *
     * <p>지금(Phase 2 Task 1 시점)은 컨트롤러가 0개라 실제 집합이 공집합이고, 이 목록과
     * 비교하면 <b>정상적으로 RED</b> 다 — 하한 단언과 같은 취지로, 5개가 나타나야 GREEN 이 된다.
     */
    private static final List<String> EXPECTED_PUBLIC_ENDPOINTS = List.of(
            "GET /academies/search",
            "POST /auth/signup",
            "POST /auth/login",
            "POST /auth/refresh",
            "POST /auth/recover");

    /**
     * 컨트롤러 소스 루트가 실제로 존재하는지 먼저 확인한다.
     * 경로가 어긋나면 아래 테스트가 <b>검사한 게 없어서</b> 초록이 되는데, 그건 규칙이 지켜진 것과
     * 구별되지 않는다.
     */
    @Test
    void 컨트롤러_소스_루트가_존재한다() {
        assertThat(SOURCE_ROOT).as("테스트 작업 디렉토리가 backend/ 가 아니면 경로를 고쳐야 한다").isDirectory();
    }

    @Test
    void 컨트롤러에_역할_문자열이_없다() {
        assertThat(violations("hasRole(", "hasAnyRole("))
                .as("컨트롤러는 역할이 아니라 권한으로 인가한다 — global/security/authz 의 애너테이션을 쓴다")
                .isEmpty();
    }

    @Test
    void 컨트롤러에_PreAuthorize_가_직접_붙지_않는다() {
        assertThat(violations("@PreAuthorize"))
                .as("표현식을 컨트롤러에 직접 쓰면 인가 어휘가 다시 흩어진다 — 권한 애너테이션을 새로 만들어 쓴다")
                .isEmpty();
    }

    /**
     * 인가 애너테이션이 없는 <b>매핑 메서드</b>를 찾는다 — 파일 단위가 아니라 메서드 단위로 본다.
     *
     * <p>매핑 메서드가 "보호됨"으로 인정되는 조건은 둘 중 하나다.
     * <ul>
     *   <li>그 컨트롤러 <b>클래스 레벨</b>에 인가 애너테이션이 있다.</li>
     *   <li>그 <b>메서드 자신</b>의 애너테이션에 인가 애너테이션이 있다(메서드 레벨이 클래스 레벨을 이긴다).</li>
     * </ul>
     * 어느 쪽도 아니면 위반이다. 컨트롤러가 0개인 지금은 스캔 결과가 비어 있어 이 테스트가
     * 공허하게 통과하지만, 이는 "규칙이 지켜졌다"가 아니라 "검사할 컨트롤러가 아직 없다"는 뜻이다.
     */
    @Test
    void 인가_애너테이션이_없는_매핑_메서드가_없다() {
        List<String> authzAnnotationNames = authzAnnotationNames();
        List<MappingMethod> mappings = allMappingMethods(authzAnnotationNames);

        List<String> unguarded = new ArrayList<>();
        for (MappingMethod m : mappings) {
            String key = m.fileName() + "#" + m.methodName();
            if (EXEMPT_FILES.contains(m.fileName()) || METHOD_LEVEL_EXEMPT.contains(key)) {
                continue;
            }
            if (!m.classLevelProtected() && !m.methodLevelProtected()) {
                unguarded.add(key);
            }
        }
        assertThat(unguarded)
                .as("매핑 메서드에 인가 애너테이션이 없다(파일명#메서드명) — 클래스 레벨이나 메서드 레벨에 "
                        + "global/security/authz 의 애너테이션을 붙이거나, 의도적으로 여는 것이면 "
                        + "METHOD_LEVEL_EXEMPT 나 EXEMPT_FILES 에 근거와 함께 등록한다")
                .isEmpty();
    }

    /**
     * 조율자 Ruling 73 — 검사 대상 핸들러 수의 하한을 건다.
     *
     * <p>Phase 0 에서는 컨트롤러가 0개라 {@link #인가_애너테이션이_없는_매핑_메서드가_없다} 가
     * "위반이 없다"와 "검사할 게 없다"를 구별하지 못한 채 늘 초록이었다 — 이 테스트가 그 구멍을 막는다.
     *
     * <p><b>지금(Phase 2 Task 1 시점)은 컨트롤러가 아직 0개라 이 단언이 RED 다 — 정상이다.</b>
     * Task 3·4 가 이 어휘 위에 실제 컨트롤러를 얹으면 GREEN 으로 전환된다(조율자가 확인 예정).
     * RED 를 감추려고 {@code @Disabled} 를 붙이지 않는다 — "검사 대상 없음"과 "규칙 준수"를 다시
     * 구별 못 하게 되어 이 테스트를 추가한 이유 자체가 사라진다.
     *
     * <p>하한값 1 은 잠정값이다 — Task 3·4 가 컨트롤러를 만들면 그때의 실제 핸들러 수(예: Phase 2
     * 엔드포인트 11개)로 올리는 것이 맞다. 지금은 "0개는 안 된다"만 고정한다.
     */
    @Test
    void 검사_대상_핸들러가_하나도_없으면_실패한다() {
        List<String> authzAnnotationNames = authzAnnotationNames();
        List<MappingMethod> mappings = allMappingMethods(authzAnnotationNames);

        assertThat(mappings.size())
                .as("인가 검사 대상 매핑 메서드가 최소 1개는 있어야 한다 — 0개면 위의 인가 검사들이 "
                        + "'규칙 준수'와 '검사 대상 없음'을 구별하지 못한 채 항상 통과한다. 하한값 1 은 "
                        + "잠정값이며 Task 3·4 가 컨트롤러를 만들면 실제 필요 핸들러 수로 올린다")
                .isGreaterThanOrEqualTo(1);
    }

    /**
     * 조율자 Ruling 77 — {@code @PublicEndpoint} 는 탈출구(누구나 붙이면 인증을 생략시킬 수 있다)라
     * 붙은 엔드포인트의 집합을 {@link #EXPECTED_PUBLIC_ENDPOINTS} 허용 목록과 <b>양방향</b>으로
     * 정확히 대조한다. {@code containsExactlyInAnyOrderElementsOf} 를 쓰므로 두 방향 모두 잡는다.
     * <ul>
     *   <li>목록에 없는 엔드포인트에 새로 붙이면(과다 공개) — 실제 집합에 목록에 없는 원소가 생겨 실패</li>
     *   <li>목록에 있는데 애너테이션이 빠지면(엔드포인트 실종·이름 변경) — 실제 집합에서 그 원소가 빠져 실패</li>
     * </ul>
     * 누가 민감한 엔드포인트에 실수로(혹은 편의상) {@code @PublicEndpoint} 를 붙여도 이 목록을
     * 함께 고치지 않으면 통과하지 않으므로, 공개 범위를 넓히는 것이 항상 의도적이고 리뷰 가능한
     * 행위가 된다.
     *
     * <p><b>지금은 컨트롤러가 0개라 실제 집합이 공집합이고, RED 다 — 정상이다.</b> Task 3 가
     * §2.1·2.2·2.5·2.6·2.9 를 저 경로 그대로 구현하면 GREEN 으로 전환된다.
     */
    @Test
    void PublicEndpoint_가_붙은_엔드포인트_집합이_허용목록과_정확히_일치한다() {
        List<String> authzAnnotationNames = authzAnnotationNames();
        List<MappingMethod> mappings = allMappingMethods(authzAnnotationNames);

        List<String> actualPublicEndpoints = mappings.stream()
                .filter(m -> m.protectingAnnotations().contains("PublicEndpoint"))
                .map(MappingMethod::endpointId)
                .toList();

        assertThat(actualPublicEndpoints)
                .as("@PublicEndpoint 가 붙은 엔드포인트 집합은 EXPECTED_PUBLIC_ENDPOINTS 허용 목록과 "
                        + "정확히 같아야 한다 — 새로 붙었거나(과다 공개) 목록에 있는데 사라지면(엔드포인트 실종) "
                        + "둘 다 실패한다")
                .containsExactlyInAnyOrderElementsOf(EXPECTED_PUBLIC_ENDPOINTS);
    }

    /**
     * Ruling 103 — {@code PublicEndpoints} 프로덕션 상수(시큐리티 매처가 실제로 쓰는 값)까지 더해
     * 3원 대조로 만든다: ① 소스의 {@code @PublicEndpoint} 애너테이션(위 테스트) ② 이 테스트가 보는
     * {@code PublicEndpoints} 상수 ③ {@link #EXPECTED_PUBLIC_ENDPOINTS} 하드코딩 목록.
     *
     * <p>{@code EXPECTED_PUBLIC_ENDPOINTS} 는 여전히 하드코딩을 유지한다(Ruling 103) —
     * {@code PublicEndpoints} 를 참조해 만들면 그 상수 자체가 잘못 채워져도 대조 대상이 같은 값을
     * 베껴 항상 일치해 버려 회귀를 못 잡는다. 세 값의 출처(소스 애너테이션·상수 클래스·사양에서 손으로
     * 옮긴 리터럴)가 서로 독립적이어야 어느 한쪽의 실수를 다른 쪽이 잡는다.
     */
    @Test
    void PublicEndpoints_상수는_EXPECTED_PUBLIC_ENDPOINTS_와_정확히_일치한다() {
        List<String> fromConstant = new ArrayList<>();
        src.backend.global.security.PublicEndpoints.GET_ENDPOINTS.forEach(path -> fromConstant.add("GET " + path));
        src.backend.global.security.PublicEndpoints.POST_ENDPOINTS.forEach(path -> fromConstant.add("POST " + path));

        assertThat(fromConstant)
                .as("PublicEndpoints(SecurityConfig 가 실제로 쓰는 상수)와 EXPECTED_PUBLIC_ENDPOINTS(사양에서 "
                        + "손으로 옮긴 하드코딩) 가 어긋난다 — 둘 중 하나가 최신 사양을 놓쳤다")
                .containsExactlyInAnyOrderElementsOf(EXPECTED_PUBLIC_ENDPOINTS);
    }

    /**
     * @param endpointId 매핑 애너테이션에서 뽑은 {@code "HTTP메서드 경로"}(예: {@code "POST /auth/login"}).
     *                   경로 리터럴을 못 찾으면 {@code "?"}. {@code @MessageMapping}(STOMP)은
     *                   HTTP 메서드가 없어 애너테이션 이름 대문자(예: {@code "MESSAGEMAPPING"})를 대신 쓴다.
     * @param protectingAnnotations 이 메서드에 실효되는 인가 애너테이션 이름들 — 메서드 레벨이 있으면
     *                              그것만, 없으면 클래스 레벨 것들(메서드 레벨이 클래스 레벨을 이긴다).
     */
    private record MappingMethod(String fileName, String methodName, String endpointId,
                                  boolean classLevelProtected, boolean methodLevelProtected,
                                  List<String> protectingAnnotations) {}

    /** 이 패키지에 실존하는 인가 애너테이션의 단순 이름 목록(확장자 제거). 하드코딩하지 않고 매번 스캔한다. */
    private List<String> authzAnnotationNames() {
        if (!Files.isDirectory(AUTHZ_ANNOTATION_ROOT)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(AUTHZ_ANNOTATION_ROOT)) {
            return paths.map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".java"))
                    .map(name -> name.substring(0, name.length() - ".java".length()))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<MappingMethod> allMappingMethods(List<String> authzAnnotationNames) {
        List<MappingMethod> result = new ArrayList<>();
        for (Path file : restControllerSources()) {
            result.addAll(mappingMethodsIn(file, authzAnnotationNames));
        }
        return result;
    }

    /**
     * 한 컨트롤러 파일에서 매핑 메서드를 뽑는다. 실제 자바 파서가 아니라 줄 단위 텍스트 스캔이다 —
     * 이 저장소 컨트롤러가 전부 "public 반환타입 메서드명(" 이 한 줄에 있고 메서드 본문에 애너테이션
     * 형태의 줄이 나올 수 없다는 자바 문법 제약에 기대는 단순화다. 클래스 레벨 애너테이션은 class
     * 선언 줄 이전, 메서드 레벨 애너테이션은 직전 "public ...(" 줄과 그 앞의 "public ...(" 줄
     * (생성자 포함) 사이에서만 찾는다.
     */
    private List<MappingMethod> mappingMethodsIn(Path file, List<String> authzAnnotationNames) {
        List<String> lines = readLines(file);
        String fileName = file.getFileName().toString();
        String className = fileName.substring(0, fileName.length() - ".java".length());

        int classDeclIndex = indexOfClassDecl(lines, className);
        List<String> classLevelAnnotations = authzAnnotationsPresent(lines, 0, classDeclIndex, authzAnnotationNames);

        List<Integer> methodStarts = publicMemberSignatureIndices(lines);

        List<MappingMethod> methods = new ArrayList<>();
        int zoneStart = classDeclIndex + 1;
        for (int idx : methodStarts) {
            String mappingLine = mappingAnnotationLine(lines, zoneStart, idx);
            if (mappingLine != null) {
                List<String> methodLevelAnnotations =
                        authzAnnotationsPresent(lines, zoneStart, idx, authzAnnotationNames);
                List<String> effectiveAnnotations =
                        methodLevelAnnotations.isEmpty() ? classLevelAnnotations : methodLevelAnnotations;
                methods.add(new MappingMethod(fileName, extractMethodName(lines.get(idx)), endpointId(mappingLine),
                        !classLevelAnnotations.isEmpty(), !methodLevelAnnotations.isEmpty(), effectiveAnnotations));
            }
            zoneStart = idx + 1;
        }
        return methods;
    }

    /** {@code "HTTP메서드 경로"} 식별자를 만든다 — 클래스·메서드 이름이 아니라 사양의 실제 계약으로 대조하기 위해서다. */
    private String endpointId(String mappingLine) {
        String annotationName = MAPPING_ANNOTATIONS.stream()
                .filter(a -> mappingLine.equals(a) || mappingLine.startsWith(a + "("))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("매핑 애너테이션이 아닌 줄이다: " + mappingLine));

        String httpMethod = HTTP_METHOD_BY_MAPPING_ANNOTATION.get(annotationName);
        if (httpMethod == null) {
            Matcher requestMethod = Pattern.compile("RequestMethod\\.(\\w+)").matcher(mappingLine);
            httpMethod = requestMethod.find() ? requestMethod.group(1) : annotationName.substring(1).toUpperCase();
        }
        Matcher pathLiteral = Pattern.compile("\"([^\"]+)\"").matcher(mappingLine);
        String path = pathLiteral.find() ? pathLiteral.group(1) : "?";
        return httpMethod + " " + path;
    }

    private int indexOfClassDecl(List<String> lines, String className) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("class " + className)) {
                return i;
            }
        }
        throw new IllegalStateException("class 선언을 못 찾았다: " + className);
    }

    /** "public 반환타입 메서드명(" 형태의 줄만 고른다 — 생성자("public 클래스명(")는 토큰이 하나뿐이라 제외된다. */
    private List<Integer> publicMemberSignatureIndices(List<String> lines) {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (!trimmed.startsWith("public ") || !trimmed.contains("(")) {
                continue;
            }
            String[] tokens = beforeParen(trimmed).split("\\s+");
            if (tokens.length >= 2) {
                indices.add(i);
            }
        }
        return indices;
    }

    private String extractMethodName(String signatureLine) {
        String[] tokens = beforeParen(signatureLine.trim()).split("\\s+");
        return tokens[tokens.length - 1];
    }

    private String beforeParen(String trimmedLine) {
        return trimmedLine.substring("public ".length(), trimmedLine.indexOf('(')).trim();
    }

    /** 주어진 구간(클래스 레벨 또는 메서드 레벨)에 실존하는 인가 애너테이션 이름들을 전부 모은다. */
    private List<String> authzAnnotationsPresent(List<String> lines, int fromInclusive, int toExclusive,
                                                  List<String> authzAnnotationNames) {
        List<String> found = new ArrayList<>();
        for (int i = fromInclusive; i < toExclusive; i++) {
            String trimmed = lines.get(i).trim();
            for (String annotationName : authzAnnotationNames) {
                if (trimmed.equals("@" + annotationName) || trimmed.startsWith("@" + annotationName + "(")) {
                    found.add(annotationName);
                }
            }
        }
        return found;
    }

    /** 구간 안의 매핑 애너테이션 줄 원문을 돌려준다(경로·HTTP 메서드 추출용) — 없으면 {@code null}. */
    private String mappingAnnotationLine(List<String> lines, int fromInclusive, int toExclusive) {
        for (int i = fromInclusive; i < toExclusive; i++) {
            String trimmed = lines.get(i).trim();
            for (String annotation : MAPPING_ANNOTATIONS) {
                if (trimmed.equals(annotation) || trimmed.startsWith(annotation + "(")) {
                    return trimmed;
                }
            }
        }
        return null;
    }

    /** 위반 지점을 {@code 파일:줄: 내용} 형태로 모은다 — 실패 메시지만 보고 고칠 곳을 알 수 있게. */
    private List<String> violations(String... forbidden) {
        List<String> found = new ArrayList<>();
        for (Path file : restControllerSources()) {
            List<String> lines = readLines(file);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                for (String token : forbidden) {
                    if (line.contains(token)) {
                        found.add(file.getFileName() + ":" + (i + 1) + ": " + line.trim());
                    }
                }
            }
        }
        return found;
    }

    /** {@code @RestController} 애너테이션이 실제로 붙은 소스만 고른다 — 파일명·패키지 규칙에 기대지 않는다. */
    private List<Path> restControllerSources() {
        if (!Files.isDirectory(SOURCE_ROOT)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(SOURCE_ROOT)) {
            return paths.filter(p -> p.getFileName().toString().endsWith(".java"))
                    .filter(this::isRestController)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final String REST_CONTROLLER_ANNOTATION = "@RestController";

    /** {@code @RestController} 만 인정한다 — {@code @RestControllerAdvice} 는 접두어가 같아 별도로 걸러낸다. */
    private boolean isRestController(Path file) {
        return readLines(file).stream().anyMatch(line -> {
            String trimmed = line.trim();
            if (!trimmed.startsWith(REST_CONTROLLER_ANNOTATION)) {
                return false;
            }
            if (trimmed.length() == REST_CONTROLLER_ANNOTATION.length()) {
                return true;
            }
            char next = trimmed.charAt(REST_CONTROLLER_ANNOTATION.length());
            return !Character.isLetter(next);
        });
    }

    private List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
