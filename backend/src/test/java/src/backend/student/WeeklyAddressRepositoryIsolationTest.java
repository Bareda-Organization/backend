package src.backend.student;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * 주소 행({@code weekly_address})은 학생 모듈이 소유한다 — 다른 모듈은 {@code DailyStopLookup} 같은 학생 모듈의 읽기 포트로만 읽는다.
 * 퇴원 학생 파기 배치({@code StudentAnonymizationService})만 이 저장소를 직접 쓴다.
 *
 * <p>구조 검사다 — 동작 단언은 직접 읽는 구현도 통과시키므로 정규화된 이름 전체를 소스에서 찾는다. 상대 경로라 작업 디렉토리가
 * {@code backend/} 일 때만 성립한다.
 */
class WeeklyAddressRepositoryIsolationTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java/src/backend");

    private static final String REPOSITORY = "src.backend.student.repository.WeeklyAddressRepository";

    /** 이 저장소를 직접 쓰는 것이 허용된 파일 — 학생 모듈 안과 퇴원 학생 파기 배치. */
    private static final List<String> ALLOWED = List.of("student/", "global/retention/StudentAnonymizationService.java");

    @Test
    void 프로덕션_소스가_실제로_수집된다() {
        assertThat(프로덕션_소스()).as("하나도 못 찾으면 아래 단언은 무엇도 보장하지 않는다").isNotEmpty();
    }

    @Test
    void 학생_모듈_밖에서는_주소_행_저장소를_직접_쓰지_않는다() {
        List<String> violations = 프로덕션_소스().stream()
                .map(source -> SOURCE_ROOT.relativize(source).toString())
                .filter(relative -> ALLOWED.stream().noneMatch(relative::startsWith))
                .filter(relative -> 본문(SOURCE_ROOT.resolve(relative)).contains(REPOSITORY))
                .toList();

        assertThat(violations).as("학생 모듈의 읽기 포트(DailyStopLookup 등)를 거쳐야 한다").isEmpty();
    }

    private List<Path> 프로덕션_소스() {
        try (Stream<Path> paths = Files.walk(SOURCE_ROOT)) {
            return paths.filter(path -> path.toString().endsWith(".java")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String 본문(Path source) {
        try {
            return Files.readString(source);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
