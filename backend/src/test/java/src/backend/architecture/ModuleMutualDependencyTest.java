package src.backend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * 도메인 모듈끼리 <b>서로를 import 하는 쌍</b>이 {@code ARCHITECTURE §3.3} 의 허용 목록과 같은지 센다(BR-232).
 * 목록 밖에 새 쌍이 생기면 실패한다 — 방향을 지키는 검사가 없어 순환이 늘어도 초록이던 것을 막는다. 목록에 남았는데 이미
 * 끊어진 쌍이 있어도 실패한다(목록은 줄어드는 쪽으로만 움직인다).
 *
 * <p>소스를 직접 훑는다(외부 라이브러리 없이). 세는 것은 {@code import src.backend.<모듈>.} 줄이고, 횡단 계층
 * {@code global} · {@code observability} · 비운영 {@code demo} 는 대상에서 뺀다. 이미 있는 쌍을 코드에서 없애는 리팩터링은
 * 이번 범위 밖이다 — 그때 이 목록과 문서 표에서 함께 지운다.
 */
class ModuleMutualDependencyTest {

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java", "src", "backend");

    private static final Set<String> EXCLUDED_MODULES = Set.of("global", "observability", "demo");

    private static final Pattern IMPORT = Pattern.compile("^import\\s+(?:static\\s+)?src\\.backend\\.(\\w+)\\.");

    /** {@code ARCHITECTURE §3.3} 의 "서로 참조하는 쌍" 표와 같아야 한다 — 사전순 {@code 앞↔뒤}. */
    private static final Set<String> ALLOWED_MUTUAL_PAIRS = Set.of(
            "academy↔account", "academy↔run", "academy↔student", "account↔audit", "account↔student",
            "boarding↔exception", "boarding↔routing", "boarding↔run", "boarding↔student", "location↔student",
            "manager↔run", "request↔routing", "request↔run", "request↔student", "routing↔run", "routing↔student",
            "run↔student");

    @Test
    void 서로_import_하는_모듈_쌍은_허용_목록과_같다() throws IOException {
        Map<String, Set<String>> importsByModule = importedModulesByModule();

        Set<String> actual = new TreeSet<>();
        importsByModule.forEach((from, targets) -> targets.stream()
                .filter(to -> from.compareTo(to) < 0 && importsByModule.getOrDefault(to, Set.of()).contains(from))
                .forEach(to -> actual.add(from + "↔" + to)));

        Set<String> added = new TreeSet<>(actual);
        added.removeAll(ALLOWED_MUTUAL_PAIRS);
        Set<String> gone = new TreeSet<>(ALLOWED_MUTUAL_PAIRS);
        gone.removeAll(actual);
        assertThat(added).as("허용 목록에 없는 새 양방향 참조 — 한쪽을 이벤트·포트로 끊거나, 이유와 함께 ARCHITECTURE §3.3 표·이 목록에 더한다")
                .isEmpty();
        assertThat(gone).as("이미 끊어진 쌍이 목록에 남아 있다 — ARCHITECTURE §3.3 표와 이 목록에서 지운다").isEmpty();
    }

    private static Map<String, Set<String>> importedModulesByModule() throws IOException {
        Map<String, Set<String>> result = new TreeMap<>();
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String module = SOURCE_ROOT.relativize(file).getName(0).toString();
                if (EXCLUDED_MODULES.contains(module)) {
                    continue;
                }
                for (String line : Files.readAllLines(file)) {
                    Matcher matcher = IMPORT.matcher(line);
                    if (matcher.find() && !matcher.group(1).equals(module)
                            && !EXCLUDED_MODULES.contains(matcher.group(1))) {
                        result.computeIfAbsent(module, key -> new TreeSet<>()).add(matcher.group(1));
                    }
                }
            }
        }
        return result;
    }
}
