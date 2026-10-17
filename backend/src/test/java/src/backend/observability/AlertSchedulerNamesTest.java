package src.backend.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * {@code alerts.yml} 이 {@code code_namespace} 로 부르는 스케줄러 클래스가 실제로 있고 {@code @Scheduled} 메서드를 가졌는지 확인한다 — 클래스를 옮기거나
 * 이름을 바꾸면 경보가 값 없이 영영 조용해진다. 설정 문자열끼리 대조하는 시험이 아니라 설정 파일을 실제 코드와 대조하는 시험이다.
 */
class AlertSchedulerNamesTest {

    private static final Path ALERTS = Path.of("..", "infra", "observability", "prometheus", "alerts.yml");

    /** {@code code_namespace="a.b.C"} 또는 {@code code_namespace=~"a\\.b\\.C|a\\.b\\.D"} 의 따옴표 안쪽. */
    private static final Pattern NAMESPACE = Pattern.compile("code_namespace=~?\"([^\"]+)\"");

    @Test
    void 경보가_부르는_스케줄러_클래스는_모두_실제_스케줄러다() throws IOException {
        String yaml = Files.readString(ALERTS);

        assertThat(namesIn(yaml)).as("alerts.yml 에서 찾은 code_namespace — 0 이면 이 검사가 아무것도 못 본 것이다").hasSizeGreaterThanOrEqualTo(7);
        assertThat(unresolvedIn(yaml)).as("클래스가 없거나 @Scheduled 메서드가 없는 code_namespace").isEmpty();
    }

    /** 파일 내용과 무관한 고정 예시로 검사기 자체를 확인한다 — 실제 클래스 하나와 없는 클래스 하나를 정규식 형태로도 적어 본다. */
    @Test
    void 클래스_이름이_틀리면_찾아낸다() {
        String sample = """
                expr: x{code_namespace="src.backend.schedule.scheduler.DailyRunGenerator"}
                expr: y{code_namespace=~"src\\\\.backend\\\\.run\\\\.scheduler\\\\.RunConfirmationScheduler|src\\\\.backend\\\\.run\\\\.scheduler\\\\.NoSuchScheduler"}
                """;

        assertThat(unresolvedIn(sample)).containsExactly("src.backend.run.scheduler.NoSuchScheduler");
    }

    private static List<String> unresolvedIn(String yaml) {
        List<String> unresolved = new ArrayList<>();
        for (String name : namesIn(yaml)) {
            if (!isSchedulerClass(name)) {
                unresolved.add(name);
            }
        }
        return unresolved;
    }

    /** yaml 안에 적힌 정규식의 {@code \\.} 를 {@code .} 로 풀어 클래스 이름 목록을 만든다. */
    private static TreeSet<String> namesIn(String yaml) {
        TreeSet<String> names = new TreeSet<>();
        Matcher matcher = NAMESPACE.matcher(yaml);
        while (matcher.find()) {
            for (String alternative : matcher.group(1).split("\\|")) {
                names.add(alternative.replace("\\\\.", ".").replace("\\.", "."));
            }
        }
        return names;
    }

    private static boolean isSchedulerClass(String name) {
        try {
            Class<?> type = Class.forName(name);
            return Arrays.stream(type.getDeclaredMethods()).anyMatch(AlertSchedulerNamesTest::isScheduled);
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static boolean isScheduled(Method method) {
        return method.isAnnotationPresent(Scheduled.class);
    }
}
