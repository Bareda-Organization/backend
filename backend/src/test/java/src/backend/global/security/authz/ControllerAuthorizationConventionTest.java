package src.backend.global.security.authz;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * 컨트롤러가 인가를 권한 애너테이션으로만 표현하는지 소스 수준에서 고정하는 회귀 방지 테스트.
 *
 * <p>규칙을 문서와 주석에만 적어 두면 다음 사람이 습관대로
 * {@code @PreAuthorize("hasAnyRole('ACADEMY_ADMIN', 'PLATFORM_ADMIN')")} 한 줄을 새 엔드포인트에 붙여도
 * 아무것도 막지 않는다. 그 한 줄이 늘어날 때마다 "새 역할 추가 = 부여표 1파일" 이라는 이 구조의 이점이
 * 조용히 깎이므로, 되돌아가는 것 자체를 실패로 만든다.
 *
 * <p>런타임 인가 동작이 아니라 <b>소스 텍스트</b>를 검사한다. 애너테이션이 어느 역할로 확장되는지는
 * {@link RolePermissionsTest} 가, 실제 200/403 은 컨트롤러별 슬라이스 테스트가 담당한다.
 */
class ControllerAuthorizationConventionTest {

    private static final Path CONTROLLER_ROOT = Path.of("src/main/java/src/backend");

    /**
     * 컨트롤러 파일이 실제로 스캔됐는지 먼저 확인한다.
     * 경로가 어긋나 0개를 훑으면 아래 두 테스트가 <b>검사한 게 없어서</b> 초록이 되는데,
     * 그건 규칙이 지켜진 것과 구별되지 않는다.
     */
    @Test
    void 컨트롤러_소스를_실제로_스캔한다() {
        assertThat(CONTROLLER_ROOT).as("테스트 작업 디렉토리가 backend/ 가 아니면 경로를 고쳐야 한다").isDirectory();
        assertThat(controllerSources()).as("스캔된 컨트롤러 파일").hasSizeGreaterThanOrEqualTo(14);
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

    /** 메서드 위에 붙을 수 있는 매핑 애너테이션 — 이 중 하나가 있어야 "엔드포인트 메서드"로 센다. */
    private static final List<String> MAPPING_ANNOTATIONS = List.of(
            "@GetMapping", "@PostMapping", "@PutMapping", "@PatchMapping", "@DeleteMapping",
            "@RequestMapping", "@MessageMapping");

    /**
     * {@code 파일명#메서드명} 형태로 적는 예외 목록 — 여기 없는 매핑은 클래스 레벨이나 메서드 레벨에
     * {@code @Can*} 이 반드시 있어야 한다.
     *
     * <p><b>이 목록에 항목을 추가할 때는 서비스 계층에서 범위 검증이 실제로 이뤄지는지 확인하고,
     * 그 근거를 여기에 적는다.</b> 이름만 적고 근거가 없으면, 같은 파일에 인가 없는 매핑이 하나 더
     * 늘어도 이 목록이 통째로 가려 버려 회귀를 못 잡는다.
     */
    private static final List<String> METHOD_LEVEL_EXEMPT = List.of(
            // RouteController#stops: 학원 구성원이면 역할 무관하게 조회할 수 있게 의도적으로 열려 있다.
            // 학원 경계(다른 학원 노선 차단)는 RouteQueryService.getStops 가 서비스 계층에서 검증한다
            // (2026-08-23 리뷰 ① 수정 결과 — route.getTenant().getId() 와 AuthUser.belongsToTenant 비교).
            "RouteController.java#stops",
            // LocationSocketController#report: STOMP @MessageMapping 은 @Can* 인가 체계가 적용되지
            // 않는 통로다(인가는 CONNECT 시 1회 인증된 Principal 로 이루어진다). LocationCommandService
            // .reportSelf 가 principal 의 userId 로만 자기 Student 레코드를 조회해 다른 사용자 데이터에
            // 닿을 수단이 없다 — 서비스 계층 자체가 "자기 자신"으로 스코프를 고정한다.
            "LocationSocketController.java#report"
    );

    /**
     * 인가 애너테이션이 없는 <b>매핑 메서드</b>를 찾는다 — 파일 단위가 아니라 메서드 단위로 본다.
     *
     * <p>이전 버전(2026-08-23 리뷰 ①)은 파일 단위였다 — 파일 안에 {@code @Can} 문자열이 하나라도
     * 있으면 그 파일의 <b>모든</b> 매핑이 통과했다. 그래서 {@code GET /api/routes/{id}/stops} 가
     * 인가 없이 배포됐을 때도, <b>그 결함을 고친 뒤에도</b> 같은 파일에 인가 없는 매핑이 하나 더
     * 늘면 여전히 초록이 났다(2026-08-23 리뷰 ②, Important #4·#5) — "이 파일에 인가가 존재한다"만
     * 보고 "이 매핑이 인가를 받는다"는 보지 않았기 때문이다.
     *
     * <p>매핑 메서드가 "보호됨"으로 인정되는 조건은 둘 중 하나다.
     * <ul>
     *   <li>그 컨트롤러 <b>클래스 레벨</b>에 {@code @Can*} 이 있다(예: {@code BusController}).</li>
     *   <li>그 <b>메서드 자신</b>의 애너테이션에 {@code @Can*} 이 있다(예: {@code BusController#myBus}
     *       의 {@code @CanReadAssignedBus} — 메서드 레벨이 클래스 레벨을 이긴다).</li>
     * </ul>
     * 어느 쪽도 아니면 위반이다. {@code AuthController} 의 매핑은 전부 예외다(로그인·회원가입·토큰
     * 재발급 — 인증 자체가 아직 없는 permitAll 경로). 그 외 예외는 {@link #METHOD_LEVEL_EXEMPT} 를 본다.
     */
    @Test
    void 인가_애너테이션이_없는_매핑_메서드가_없다() {
        List<MappingMethod> mappings = allMappingMethods();
        // 0개를 훑고 통과하는 상태와 구별해야 한다 — 실측 86개(2026-08-23) 대비 여유 있게 80으로 잡는다.
        assertThat(mappings).as("스캔된 매핑 메서드 수").hasSizeGreaterThanOrEqualTo(80);

        List<String> unguarded = new ArrayList<>();
        for (MappingMethod m : mappings) {
            String key = m.fileName() + "#" + m.methodName();
            if (m.fileName().equals("AuthController.java") || METHOD_LEVEL_EXEMPT.contains(key)) {
                continue;
            }
            if (!m.classLevelProtected() && !m.methodLevelProtected()) {
                unguarded.add(key);
            }
        }
        assertThat(unguarded)
                .as("매핑 메서드에 인가 애너테이션이 없다(파일명#메서드명) — 클래스 레벨이나 메서드 레벨에 "
                        + "@Can* 을 붙이거나, 의도적으로 여는 것이면 METHOD_LEVEL_EXEMPT 에 근거와 함께 등록한다")
                .isEmpty();
    }

    private record MappingMethod(String fileName, String methodName,
                                  boolean classLevelProtected, boolean methodLevelProtected) {}

    private List<MappingMethod> allMappingMethods() {
        List<MappingMethod> result = new ArrayList<>();
        for (Path file : controllerSources()) {
            result.addAll(mappingMethodsIn(file));
        }
        return result;
    }

    /**
     * 한 컨트롤러 파일에서 매핑 메서드를 뽑는다. 실제 자바 파서가 아니라 줄 단위 텍스트 스캔이다 —
     * 이 저장소 컨트롤러가 전부 "public 반환타입 메서드명(" 이 한 줄에 있고 메서드 본문에 애너테이션
     * 형태의 줄(예: 트림했을 때 {@code @Can} 으로 시작하는 줄)이 나올 수 없다는 자바 문법 제약에
     * 기대는 단순화다 — 클래스 레벨 애너테이션은 class 선언 줄 이전, 메서드 레벨 애너테이션은
     * 직전 "public ...(" 줄과 그 앞의 "public ...(" 줄(생성자 포함) 사이에서만 찾는다.
     */
    private List<MappingMethod> mappingMethodsIn(Path file) {
        List<String> lines = readLines(file);
        String fileName = file.getFileName().toString();
        String className = fileName.substring(0, fileName.length() - ".java".length());

        int classDeclIndex = indexOfClassDecl(lines, className);
        boolean classLevelProtected = hasCanAnnotation(lines, 0, classDeclIndex);

        List<Integer> methodStarts = publicMemberSignatureIndices(lines);

        List<MappingMethod> methods = new ArrayList<>();
        int zoneStart = classDeclIndex + 1;
        for (int idx : methodStarts) {
            if (hasMappingAnnotation(lines, zoneStart, idx)) {
                boolean methodLevelProtected = hasCanAnnotation(lines, zoneStart, idx);
                methods.add(new MappingMethod(fileName, extractMethodName(lines.get(idx)),
                        classLevelProtected, methodLevelProtected));
            }
            zoneStart = idx + 1;
        }
        return methods;
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

    private boolean hasCanAnnotation(List<String> lines, int fromInclusive, int toExclusive) {
        for (int i = fromInclusive; i < toExclusive; i++) {
            if (lines.get(i).trim().startsWith("@Can")) {
                return true;
            }
        }
        return false;
    }

    private boolean hasMappingAnnotation(List<String> lines, int fromInclusive, int toExclusive) {
        for (int i = fromInclusive; i < toExclusive; i++) {
            String trimmed = lines.get(i).trim();
            for (String annotation : MAPPING_ANNOTATIONS) {
                if (trimmed.equals(annotation) || trimmed.startsWith(annotation + "(")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 위반 지점을 {@code 파일:줄: 내용} 형태로 모은다 — 실패 메시지만 보고 고칠 곳을 알 수 있게. */
    private List<String> violations(String... forbidden) {
        List<String> found = new ArrayList<>();
        for (Path file : controllerSources()) {
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

    private List<Path> controllerSources() {
        try (Stream<Path> paths = Files.walk(CONTROLLER_ROOT)) {
            return paths.filter(p -> p.getParent() != null && p.getParent().endsWith("controller"))
                    .filter(p -> p.getFileName().toString().endsWith("Controller.java"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
