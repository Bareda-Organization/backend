package src.backend.architecture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 구조 규칙 시험이 공통으로 쓰는 소스 훑기 — 메인 소스 루트와 시험 자원의 모의 소스 루트를 같은 방법으로 읽는다. 규칙마다
 * "허용 목록(기준선)" 을 두고, 목록 밖의 새 위반과 이미 사라졌는데 목록에 남은 항목을 둘 다 실패로 본다.
 */
final class JavaSourceScan {

    static final Path MAIN = Path.of("src", "main", "java", "src", "backend");

    static final Path FIXTURES = Path.of("src", "test", "resources", "architecture", "fixtures");

    private static final Pattern IMPORT = Pattern.compile("^import\\s+(?:static\\s+)?(src\\.backend\\.[\\w.]+?)(?:\\.\\*)?;");

    private JavaSourceScan() {
    }

    /** 루트 아래 모든 {@code .java} 파일의 상대 경로(슬래시 구분) → 파일. */
    static Map<String, Path> javaFiles(Path root) throws IOException {
        Map<String, Path> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> files.put(root.relativize(path).toString().replace('\\', '/'), path));
        }
        return files;
    }

    /** 파일이 import 한 {@code src.backend} 클래스(정규화한 전체 이름). */
    static List<String> importsOf(Path file) throws IOException {
        return Files.readAllLines(file).stream().map(IMPORT::matcher).filter(Matcher::find)
                .map(matcher -> matcher.group(1)).toList();
    }

    /**
     * 경로 조건을 만족하는 파일이 import 조건을 만족하는 클래스를 가져오면 {@code "<상대경로> -> <클래스>"} 항목으로 모은다.
     */
    static TreeSet<String> violations(Path root, Predicate<String> pathFilter, Predicate<String> importFilter)
            throws IOException {
        TreeSet<String> found = new TreeSet<>();
        for (Map.Entry<String, Path> file : javaFiles(root).entrySet()) {
            if (!pathFilter.test(file.getKey())) {
                continue;
            }
            for (String imported : importsOf(file.getValue())) {
                if (importFilter.test(imported)) {
                    found.add(file.getKey() + " -> " + imported);
                }
            }
        }
        return found;
    }

    /** 상대 경로에 {@code segment} 이름의 폴더가 있는가. */
    static boolean inPackage(String relativePath, String segment) {
        String[] parts = relativePath.split("/");
        return List.of(parts).subList(0, parts.length - 1).contains(segment);
    }

    /** import 한 클래스의 패키지에 {@code segment} 가 들어 있는가 — 마지막 토막(클래스 이름)은 보지 않는다. */
    static boolean importsFromPackage(String imported, String segment) {
        String[] parts = imported.split("\\.");
        return List.of(parts).subList(0, parts.length - 1).contains(segment);
    }

    /** 실제 위반 목록이 기준선과 같은지 확인한다 — 새 위반과 이미 사라진 기준선 항목 둘 다 실패다. */
    static void assertMatchesBaseline(java.util.Set<String> actual, java.util.Set<String> baseline, String rule) {
        TreeSet<String> added = new TreeSet<>(actual);
        added.removeAll(baseline);
        TreeSet<String> gone = new TreeSet<>(baseline);
        gone.removeAll(actual);
        assertThat(added).as(rule + " — 기준선에 없는 새 위반. 규칙을 지키게 고치거나, 이유와 함께 기준선에 더한다").isEmpty();
        assertThat(gone).as(rule + " — 이미 사라졌는데 기준선에 남은 항목. 기준선에서 지운다").isEmpty();
    }
}
