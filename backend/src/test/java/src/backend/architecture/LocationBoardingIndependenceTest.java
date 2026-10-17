package src.backend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AR-4 의 {@code location→boarding} 부분(Ruling 899) — {@code location} 모듈은 {@code boarding} 을 import 하지 않는다. 탑승 학생은
 * {@code global.location.RunRiderDirectory} 포트로 얻고 구현은 {@code boarding} 이 낸다. 방향을 되돌리는 import 하나가 순환을 되살린다.
 */
class LocationBoardingIndependenceTest {

    private static final Path LOCATION = Path.of("src", "main", "java", "src", "backend", "location");

    @Test
    @DisplayName("AR-4 — location 의 운영 소스는 src.backend.boarding 을 import 하지 않는다")
    void location_은_boarding_을_import_하지_않는다() throws IOException {
        assertThat(LOCATION).as("검사 대상 경로가 있어야 빈 결과가 '위반 없음'과 구별된다").isDirectory();

        try (Stream<Path> files = Files.walk(LOCATION)) {
            List<String> violations = files.filter(path -> path.toString().endsWith(".java"))
                    .flatMap(path -> lines(path).filter(line -> line.startsWith("import ")
                            && line.contains("src.backend.boarding.")).map(line -> path + " — " + line))
                    .toList();

            assertThat(violations).isEmpty();
        }
    }

    private static Stream<String> lines(Path path) {
        try {
            return Files.readAllLines(path).stream();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
