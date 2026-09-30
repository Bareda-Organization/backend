package src.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * 스케줄러 스레드 수 = {@code @Scheduled} 메서드 수(BR-256) — {@link SchedulingConfig#POOL_SIZE} 주석이 "작업을 더하면 이 값도
 * 함께 올린다" 를 약속하지만 주석만으로는 지켜지지 않는다. 풀이 작업 수보다 작으면 어떤 작업이 남의 작업(예: 확정 배치가
 * 도는 몇 분)을 기다린다. 데모 시뮬레이터({@code @Profile("local")})도 센다 — 로컬·스테이징에서 함께 도는 작업이다.
 */
class SchedulingPoolSizeTest {

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java");

    @Test
    void 스케줄러_스레드_수는_Scheduled_메서드_수와_같다() throws IOException {
        long scheduledMethods;
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            scheduledMethods = files.filter(path -> path.toString().endsWith(".java"))
                    .flatMap(SchedulingPoolSizeTest::lines)
                    .filter(line -> line.strip().startsWith("@Scheduled("))
                    .count();
        }

        assertThat(SchedulingConfig.POOL_SIZE)
                .as("@Scheduled 메서드가 %d개인데 스케줄러 스레드가 다르다 — SchedulingConfig.POOL_SIZE 와 주석의 개수를 함께 고친다", scheduledMethods)
                .isEqualTo((int) scheduledMethods);
    }

    private static Stream<String> lines(Path file) {
        try {
            return Files.readAllLines(file).stream();
        } catch (IOException e) {
            throw new IllegalStateException(file + " 를 읽지 못했다", e);
        }
    }
}
