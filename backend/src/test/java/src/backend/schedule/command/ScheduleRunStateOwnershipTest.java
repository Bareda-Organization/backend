package src.backend.schedule.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BR-244 — {@code schedule} 모듈은 {@code Run} 의 상태를 엔티티 메서드로 직접 바꾸지 않는다(ARCHITECTURE §3.3). 취소·되살림·
 * 계획 이동은 {@code run} 모듈({@code RunCancellation}·{@code RunCommandService})이 회차를 잠그고 다시 읽은 뒤 판정하므로,
 * 여기서 엔티티를 직접 부르면 그 잠금·재판정이 우회된다(BR-204).
 */
class ScheduleRunStateOwnershipTest {

    private static final Path SCHEDULE_SOURCES = Path.of("src/main/java/src/backend/schedule");

    /** {@code run.cancel(} · {@code run.reinstate(} · {@code run.moveToPlan(} — 엔티티 전이 메서드 직접 호출. */
    private static final Pattern DIRECT_TRANSITION = Pattern.compile("\\brun\\s*\\.\\s*(cancel|reinstate|moveToPlan)\\s*\\(");

    @Test
    @DisplayName("BR-244 — schedule 모듈은 Run 의 취소·되살림·계획 이동 메서드를 직접 부르지 않는다")
    void schedule_모듈은_Run_전이_메서드를_직접_부르지_않는다() throws IOException {
        try (Stream<Path> files = Files.walk(SCHEDULE_SOURCES)) {
            List<String> violations = files.filter(p -> p.toString().endsWith(".java")).flatMap(p -> {
                try {
                    return DIRECT_TRANSITION.matcher(withoutComments(Files.readString(p))).results()
                            .map(m -> p.getFileName() + ": " + m.group().strip());
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
            assertThat(violations).isEmpty();
        }
    }

    private static String withoutComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }
}
