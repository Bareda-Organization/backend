package src.backend.global.security.access;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.core.ResolvableType;
import org.springframework.data.repository.Repository;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * {@link AcademyScopeRepositoryConventionTest} 가 쓰는 <b>수집 전담</b> 도우미 — 무엇이 규칙
 * 위반인지는 여기서 판정하지 않는다.
 *
 * <p>규칙(ERD §6.1 표 · 좁혀짐의 정의 · 예외 목록)과 수집(엔티티·저장소·소스 텍스트 긁기)을 가른
 * 이유는 바뀌는 축이 다르기 때문이다 — 규칙은 사양이 바뀔 때, 수집은 패키지 구조나 빌드 배치가
 * 바뀔 때 바뀐다. 한 클래스에 두면 사양 변경 때마다 스캔 코드를 함께 읽어야 한다.
 *
 * <p>리플렉션과 소스 텍스트를 함께 쓴다 — 반환 타입·애너테이션은 리플렉션이 정확하고, 호출부
 * ({@code findAll()} 사용 여부)는 컴파일된 클래스만으로는 필드 이름과 이어 붙이기 어렵다.
 */
final class AcademyScopeScan {

    /**
     * 스캔의 기준 경로 — 상대 경로라 <b>작업 디렉토리가 {@code backend/} 일 때만</b> 성립한다.
     * 어긋나면 모든 스캔이 공집합이 되어 규칙이 지켜진 것과 구별되지 않으므로, 호출하는 테스트가
     * 이 경로의 실재를 먼저 단언한다.
     */
    static final Path SOURCE_ROOT = Path.of("src/main/java/src/backend");

    private AcademyScopeScan() {
    }

    /** {@code repository} 패키지의 Spring Data 저장소 인터페이스 전부. */
    static List<Class<?>> repositoryInterfaces() {
        return classesUnder("repository").stream()
                .filter(Repository.class::isAssignableFrom)
                .toList();
    }

    /** {@code entity} 패키지의 JPA 엔티티 전부. */
    static List<Class<?>> entityClasses() {
        return classesUnder("entity").stream()
                .filter(candidate -> candidate.isAnnotationPresent(Entity.class))
                .toList();
    }

    /** 테이블 이름 → 엔티티. ERD 표(테이블 이름)와 코드(클래스)를 잇는 유일한 대응표다. */
    static Map<String, Class<?>> entitiesByTable() {
        Map<String, Class<?>> byTable = new LinkedHashMap<>();
        for (Class<?> entity : entityClasses()) {
            byTable.put(tableName(entity), entity);
        }
        return byTable;
    }

    /** 저장소가 다루는 엔티티 타입 — {@code Repository<T, ID>} 의 첫 제네릭 인자. */
    static Class<?> entityTypeOf(Class<?> repository) {
        ResolvableType generic = ResolvableType.forClass(repository).as(Repository.class).getGeneric(0);
        return generic.resolve();
    }

    static boolean hasAcademyIdField(Class<?> entity) {
        return Arrays.stream(entity.getDeclaredFields())
                .anyMatch(field -> field.getName().equals("academyId"));
    }

    static String tableName(Class<?> entity) {
        Table table = entity.getAnnotation(Table.class);
        return table != null ? table.name() : entity.getSimpleName();
    }

    /** 실패 메시지에 쓰는 메서드 식별자 — {@code StudentRepository#findByAccountId} 형태. */
    static String key(Method method) {
        return method.getDeclaringClass().getSimpleName() + "#" + method.getName();
    }

    /**
     * 호출부 검사 대상 소스 — {@code repository} 패키지는 제외한다. 저장소 인터페이스 자신의
     * 선언은 호출이 아니라서, 넣으면 선언만으로 위반이 잡힌다.
     */
    static List<Path> mainSources() {
        try (Stream<Path> paths = Files.walk(SOURCE_ROOT)) {
            return paths.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getParent().getFileName().toString().equals("repository"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 소스에서 주어진 타입으로 선언된 필드 이름을 뽑는다 — 호출부 검사를 필드 단위로 좁히기 위함이다. */
    static List<String> fieldNamesOfType(String source, String typeName) {
        Matcher matcher = Pattern.compile("\\b" + typeName + "\\s+(\\w+)\\s*[;=)]").matcher(source);
        List<String> names = new ArrayList<>();
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code src/main/java} 아래 지정한 이름의 패키지에 있는 클래스를 소스 경로에서 유도해 적재한다. */
    private static List<Class<?>> classesUnder(String packageSegment) {
        try (Stream<Path> paths = Files.walk(SOURCE_ROOT)) {
            return paths.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.getParent().getFileName().toString().equals(packageSegment))
                    .map(AcademyScopeScan::loadClass)
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
}
