package src.backend.global.security.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.core.ResolvableType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.Entity;

/**
 * 학원 격리를 <b>빠뜨린 저장소 조회를 실패로 만드는</b> 회귀 방지 테스트 — 이 태스크의 핵심 산출물이다.
 *
 * <p>격리는 DB 레벨(RLS)이 아니라 애플리케이션 코드가 강제하므로(CLAUDE.md), 조건 하나를 빠뜨리면
 * 그대로 샌다. 특히 목록 조회는 조건이 빠져도 동작해서 기능 테스트를 통과한다(ARCHITECTURE §6.1).
 * 그래서 "격리를 넣었는가" 가 아니라 <b>"넣지 않은 곳이 있는가"</b> 를 기계가 세게 한다.
 *
 * <p>검사는 네 축이다.
 * <ul>
 *   <li>{@code academy_id} 직접 보유 엔티티 집합이 ERD §6.1 과 일치하는가 — 격리 대상의 정의처 고정</li>
 *   <li>그 엔티티의 저장소 조회가 학원 조건을 갖거나 {@link AcademyScopeExempt} 로 예외를 밝히는가</li>
 *   <li>로그인·재발급·복구 조회 4개가 <b>예외로 남아 있는가</b> — 반대 방향(과잉 격리)의 사고 방지</li>
 *   <li>프로덕션 코드가 그 저장소의 무조건 전건 조회를 호출하지 않는가 — 상속 메서드로 새는 경로</li>
 * </ul>
 *
 * <p>리플렉션과 소스 텍스트를 함께 쓴다 — 반환 타입·애너테이션은 리플렉션이 정확하고, 호출부
 * ({@code findAll()} 사용 여부)는 컴파일된 클래스만으로는 필드 이름과 이어 붙이기 어렵다.
 */
class AcademyScopeRepositoryConventionTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java/src/backend");

    /**
     * ERD §6.1 의 <b>직접 보유</b> 표를 그대로 옮긴 테이블 목록(17개) — 코드에서 유도하지 않고 손으로 적는다.
     * 코드에서 유도하면 엔티티에 {@code academyId} 를 빠뜨리는 실수와 함께 틀려 아무것도 못 잡는다
     * ({@code PublicEndpoints} ↔ {@code EXPECTED_PUBLIC_ENDPOINTS} 와 같은 이유의 독립 축이다).
     *
     * <p>테넌트 루트인 {@code academy} 자신은 어느 분류에도 속하지 않아 대상 밖이다(ERD §6.1).
     */
    private static final Set<String> ERD_DIRECT_ACADEMY_TABLES = Set.of(
            "academy_setting", "account", "signup_request", "academy_staff",
            "student", "guardian", "manager", "bus", "stop",
            "schedule", "route", "run",
            "change_request", "notification_log",
            "audit_log", "exception_report", "emergency_alert");

    /**
     * 학원으로 좁히면 <b>깨지는</b> 조회 — 로그인·토큰 재발급·계정 복구는 소속을 아직 모르는 상태에서
     * 시작하고, 소속을 알아야 좁힐 수 있는데 좁혀야 소속을 안다는 순환이 된다(Ruling 100).
     *
     * <p>{@code AccountRepository.findByLoginId} 도 같은 성질이나, 이 목록은 조율자가 확정한 4개만
     * 담는다 — 확정 사실과 판단을 섞지 않기 위함이다.
     */
    private static final List<String> MUST_STAY_UNSCOPED = List.of(
            "AccountRepository#findByPhone",
            "RefreshTokenRepository#findByTokenHash",
            "RefreshTokenRepository#findAllByAccountIdAndRevokedAtIsNull",
            "VerificationCodeRepository#findTopByPhoneAndPurposeOrderByCreatedAtDesc");

    /** 학원 범위 저장소에 호출하면 격리가 통째로 빠지는 상속 메서드 — {@code JpaRepository} 가 전부 물려준다. */
    private static final List<String> UNSCOPED_BULK_READS = List.of("findAll(", "findAllById(", "count()");

    @Test
    void 소스_루트가_존재한다() {
        assertThat(SOURCE_ROOT).as("작업 디렉토리가 backend/ 가 아니면 아래 스캔이 전부 공집합으로 초록이 된다").isDirectory();
    }

    /**
     * 격리 대상의 정의처를 고정한다 — 엔티티에 {@code academyId} 를 새로 넣거나 빼면 이 단언이 먼저 깨진다.
     * 이 축이 없으면 아래 저장소 검사가 "대상을 못 찾아서" 조용히 통과할 수 있다.
     */
    @Test
    void academy_id_직접_보유_엔티티가_ERD_6_1_의_17개와_일치한다() {
        Set<String> actual = new LinkedHashSet<>();
        for (Class<?> entity : entityClasses()) {
            if (hasAcademyIdField(entity)) {
                actual.add(tableName(entity));
            }
        }

        assertThat(actual)
                .as("ERD §6.1 직접 보유 표와 어긋난다 — 문서와 코드 중 어느 쪽이 틀렸는지 판정하고 나서 이 목록을 고친다")
                .containsExactlyInAnyOrderElementsOf(ERD_DIRECT_ACADEMY_TABLES);
        assertThat(actual).hasSize(17);
    }

    /**
     * 직접 보유 엔티티의 저장소 조회는 학원 조건을 갖거나 예외임을 밝혀야 한다 — 둘 다 아니면 위반이다.
     * 새 조회를 아무 표시 없이 추가하는 것이 이 시스템에서 격리가 새는 표준 경로다.
     */
    @Test
    void 학원_범위_저장소의_모든_조회가_학원_조건을_갖거나_예외로_표시된다() {
        List<Method> methods = academyScopedRepositoryMethods();

        assertThat(methods)
                .as("검사 대상 조회가 0개면 이 테스트는 규칙이 지켜진 것과 구별되지 않는다")
                .isNotEmpty();

        List<String> unmarked = methods.stream()
                .filter(method -> !isNarrowedByAcademy(method))
                .filter(method -> method.getAnnotation(AcademyScopeExempt.class) == null)
                .map(AcademyScopeRepositoryConventionTest::key)
                .toList();

        assertThat(unmarked)
                .as("학원 조건도 @AcademyScopeExempt 도 없는 조회 — 격리를 빠뜨렸거나 예외 근거를 안 적었다")
                .isEmpty();
    }

    /** 예외 표시는 근거와 함께여야 한다 — 근거 없는 예외는 격리 누락과 구별되지 않는다. */
    @Test
    void 예외로_표시된_조회는_전부_근거_문구를_갖는다() {
        List<AcademyScopeExempt> exemptions = academyScopedRepositoryMethods().stream()
                .map(method -> method.getAnnotation(AcademyScopeExempt.class))
                .filter(annotation -> annotation != null)
                .toList();

        assertThat(exemptions).isNotEmpty();
        assertThat(exemptions).allSatisfy(annotation ->
                assertThat(annotation.reason()).as("reason 이 비어 있다").isNotBlank());
    }

    /**
     * 반대 방향의 사고를 막는다 — 로그인·재발급·복구 조회에 "격리를 빠뜨렸다" 며 학원 조건을 넣으면
     * 로그인이 통째로 실패한다. 애너테이션 유지와 <b>조건 부재</b>를 함께 고정해, 표시만 남기고
     * 조건을 넣는 절반짜리 변경도 실패로 만든다.
     */
    @Test
    void 로그인과_복구_경로_조회_4개는_학원으로_좁혀지지_않은_채_남는다() {
        List<Method> named = allRepositoryMethods().stream()
                .filter(method -> MUST_STAY_UNSCOPED.contains(key(method)))
                .toList();

        assertThat(named.stream().map(AcademyScopeRepositoryConventionTest::key))
                .as("Ruling 100 이 지목한 조회가 사라졌거나 이름이 바뀌었다")
                .containsExactlyInAnyOrderElementsOf(MUST_STAY_UNSCOPED);

        assertThat(named).allSatisfy(method -> {
            assertThat(method.getAnnotation(AcademyScopeExempt.class))
                    .as("%s 의 예외 표시가 사라졌다 — 다음 사람이 격리 누락으로 오인한다", key(method))
                    .isNotNull();
            assertThat(isNarrowedByAcademy(method))
                    .as("%s 에 학원 조건이 붙었다 — 소속 미상 상태에서 시작하는 흐름이라 조회가 항상 0건이 된다", key(method))
                    .isFalse();
        });
    }

    /**
     * 상속 메서드로 새는 경로를 막는다 — {@code JpaRepository.findAll()} 은 선언하지 않아도 존재하고,
     * 위 검사가 <b>선언된 메서드만</b> 보므로 여기서 따로 잡지 않으면 그대로 전 학원 조회가 된다.
     */
    @Test
    void 프로덕션_코드가_학원_범위_저장소의_전건_조회를_호출하지_않는다() {
        List<String> scopedRepositoryNames = academyScopedRepositories().stream()
                .map(Class::getSimpleName)
                .toList();
        List<String> violations = new ArrayList<>();
        int inspectedFields = 0;

        for (Path source : mainSources()) {
            String body = read(source);
            for (String repositoryName : scopedRepositoryNames) {
                for (String field : fieldNamesOfType(body, repositoryName)) {
                    inspectedFields++;
                    for (String bulkRead : UNSCOPED_BULK_READS) {
                        if (body.contains(field + "." + bulkRead)) {
                            violations.add(source.getFileName() + ": " + field + "." + bulkRead);
                        }
                    }
                }
            }
        }

        assertThat(inspectedFields)
                .as("학원 범위 저장소를 주입받은 프로덕션 필드가 0개면 이 검사는 아무것도 보지 않은 것이다")
                .isPositive();
        assertThat(violations)
                .as("학원 조건 없는 전건 조회 — 학원 조건이 붙은 조회 메서드를 저장소에 선언해 쓴다")
                .isEmpty();
    }

    // ── 스캔 도우미 ────────────────────────────────────────────────────────

    private static List<Method> academyScopedRepositoryMethods() {
        return academyScopedRepositories().stream()
                .flatMap(repository -> Arrays.stream(repository.getDeclaredMethods()))
                .toList();
    }

    private static List<Method> allRepositoryMethods() {
        return repositoryInterfaces().stream()
                .flatMap(repository -> Arrays.stream(repository.getDeclaredMethods()))
                .toList();
    }

    private static List<Class<?>> academyScopedRepositories() {
        return repositoryInterfaces().stream()
                .filter(repository -> {
                    Class<?> entity = entityTypeOf(repository);
                    return entity != null && hasAcademyIdField(entity);
                })
                .toList();
    }

    private static List<Class<?>> repositoryInterfaces() {
        return classesUnder("repository").stream()
                .filter(Repository.class::isAssignableFrom)
                .toList();
    }

    private static List<Class<?>> entityClasses() {
        return classesUnder("entity").stream()
                .filter(candidate -> candidate.isAnnotationPresent(Entity.class))
                .toList();
    }

    /** {@code src/main/java} 아래 지정한 이름의 패키지에 있는 클래스를 소스 경로에서 유도해 적재한다. */
    private static List<Class<?>> classesUnder(String packageSegment) {
        try (Stream<Path> paths = Files.walk(SOURCE_ROOT)) {
            return paths.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.getParent().getFileName().toString().equals(packageSegment))
                    .map(AcademyScopeRepositoryConventionTest::loadClass)
                    .filter(loaded -> loaded != null)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Class<?> loadClass(Path source) {
        String relative = SOURCE_ROOT.relativize(source).toString();
        String className = "src.backend." + relative.substring(0, relative.length() - ".java".length())
                .replace('/', '.');
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static Class<?> entityTypeOf(Class<?> repository) {
        ResolvableType generic = ResolvableType.forClass(repository).as(Repository.class).getGeneric(0);
        return generic.resolve();
    }

    private static boolean hasAcademyIdField(Class<?> entity) {
        return Arrays.stream(entity.getDeclaredFields())
                .anyMatch(field -> field.getName().equals("academyId"));
    }

    private static String tableName(Class<?> entity) {
        jakarta.persistence.Table table = entity.getAnnotation(jakarta.persistence.Table.class);
        return table != null ? table.name() : entity.getSimpleName();
    }

    /** 메서드 이름·{@code @Query} 본문·{@code @Param} 중 어디로든 학원 조건이 걸려 있으면 좁혀진 것으로 센다. */
    private static boolean isNarrowedByAcademy(Method method) {
        if (method.getName().contains("AcademyId")) {
            return true;
        }
        Query query = method.getAnnotation(Query.class);
        if (query != null && (query.value().contains("academyId") || query.value().contains("academy_id"))) {
            return true;
        }
        return Arrays.stream(method.getParameterAnnotations())
                .flatMap(Arrays::stream)
                .anyMatch(annotation -> annotation instanceof Param param && param.value().equals("academyId"));
    }

    private static String key(Method method) {
        return method.getDeclaringClass().getSimpleName() + "#" + method.getName();
    }

    private static List<Path> mainSources() {
        try (Stream<Path> paths = Files.walk(SOURCE_ROOT)) {
            return paths.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getParent().getFileName().toString().equals("repository"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 소스에서 주어진 타입으로 선언된 필드 이름을 뽑는다 — 호출부 검사를 필드 단위로 좁히기 위함이다. */
    private static List<String> fieldNamesOfType(String source, String typeName) {
        Matcher matcher = Pattern.compile("\\b" + typeName + "\\s+(\\w+)\\s*[;=)]").matcher(source);
        List<String> names = new ArrayList<>();
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
