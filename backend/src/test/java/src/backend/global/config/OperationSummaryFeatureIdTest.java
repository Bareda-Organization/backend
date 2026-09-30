package src.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Swagger {@code @Operation} 요약 끝에 사양 표의 ID({@code (STU-01, A-10)} 식)가 있어야 한다(BR-260) — 없으면 Swagger 화면에서
 * {@code API_SPEC} 표와 대조가 안 된다. {@code OpenApiCoverageTest} 는 요약의 <b>존재</b>만 본다. ID 는 {@code 기능 ID}
 * ({@code STU-01}) · 화면 ID({@code A-10}) · 공통 규칙({@code C-14}) 어느 것이든 하나면 된다.
 */
class OperationSummaryFeatureIdTest {

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java");

    private static final Pattern SUMMARY = Pattern.compile("@Operation\\(\\s*summary\\s*=\\s*\"([^\"]*)\"");

    private static final Pattern SPEC_ID = Pattern.compile("\\b[A-Z]{1,5}-\\d{2,3}\\b");

    /** 사양 표에 기능 ID 가 없는 예외 — 로컬 전용 개발 도구와, Ruling 254 로만 근거가 있는 강제 확정 콘솔. */
    private static final Set<String> EXEMPT_FILES = Set.of("DevResetController.java", "AdminRunForceConfirmController.java");

    @Test
    void 모든_Operation_요약에_사양_ID_가_있다() throws IOException {
        List<String> missing = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !EXEMPT_FILES.contains(path.getFileName().toString())).toList()) {
                Matcher matcher = SUMMARY.matcher(Files.readString(file));
                while (matcher.find()) {
                    if (!SPEC_ID.matcher(matcher.group(1)).find()) {
                        missing.add(file.getFileName() + " — " + matcher.group(1));
                    }
                }
            }
        }

        assertThat(missing).as("요약 끝에 사양 ID(예: (STU-01, A-10))가 없는 @Operation").isEmpty();
    }
}
