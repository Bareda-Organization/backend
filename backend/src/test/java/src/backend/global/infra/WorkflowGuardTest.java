package src.backend.global.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 백엔드 시험을 돌리는 두 워크플로({@code ci.yml} · {@code deploy-backend.yml})가 같은 입력을 준비하는지 본다 — BR-304 (R10-01).
 *
 * <p>{@code ErrorCodeSpecParityTest} 는 사양 파일({@code API_SPEC.md})을 읽는데 그 파일은 작업 공간 저장소에 있다. 러너는 backend 저장소 하나만
 * 받으므로 워크플로가 사양 파일을 따로 받아 {@code API_SPEC_PATH} 로 알려 줘야 한다. 한쪽만 고치면 다른 쪽 시험이 항상 실패한다 — 배포 워크플로가
 * 그랬다(저장소 분리 때 {@code ci.yml} 만 고쳐서, 첫 배포에서야 드러날 상태). 공개 저장소라 Actions 로그가 공개이므로 {@code -PciQuiet} 도 같이 본다.
 *
 * <p>파일 텍스트만 읽는다. 주석 줄은 걷어 내고 본다 — 주석이 옛 설정을 설명해도 통과하지 않게.
 */
class WorkflowGuardTest {

    private static final Path WORKFLOWS = Path.of("..", ".github", "workflows");

    private static final String WORKSPACE_CHECKOUT = "repository: ${{ github.repository_owner }}/workspace";

    static Stream<String> backendTestWorkflows() {
        return Stream.of("ci.yml", "deploy-backend.yml");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backendTestWorkflows")
    @DisplayName("시험 단계 앞에서 작업 공간 저장소의 사양 파일을 받고, 시험 단계가 그 경로를 API_SPEC_PATH 로 받으며 로그를 조용히 한다")
    void testStepGetsTheSpecFileFromTheWorkspaceRepository(String workflow) throws IOException {
        List<String> steps = steps(workflow);
        int checkout = indexOfStepContaining(steps, WORKSPACE_CHECKOUT);
        int test = indexOfStepContaining(steps, "./gradlew test");

        assertThat(checkout).as("%s — 작업 공간 저장소(%s)를 받는 단계가 있어야 한다", workflow, WORKSPACE_CHECKOUT).isNotNegative();
        assertThat(test).as("%s — ./gradlew test 단계", workflow).isNotNegative();
        assertThat(checkout).as("%s — 사양 파일은 시험 단계보다 먼저 받아야 한다", workflow).isLessThan(test);

        String checkoutStep = steps.get(checkout);
        String specFile = valueOf(checkoutStep, "sparse-checkout", workflow);
        String specDir = valueOf(checkoutStep, "path", workflow);
        String testStep = steps.get(test);

        assertThat(testStep).as("%s — 시험 단계가 받은 사양 파일의 경로를 API_SPEC_PATH 로 넘겨야 한다(ErrorCodeSpecParityTest 가 읽는다)", workflow)
                .contains("API_SPEC_PATH: ${{ github.workspace }}/" + specDir + "/" + specFile);
        assertThat(testStep).as("%s — 공개 저장소의 Actions 로그에 애플리케이션 로그가 실리지 않게 한다", workflow).contains("-PciQuiet");
    }

    /** 스텝 목록 — 이 저장소 워크플로의 스텝은 전부 6칸 들여쓴 {@code - } 로 시작한다. 주석 줄은 걷어 낸다. */
    private static List<String> steps(String workflow) throws IOException {
        String yaml = stripComments(Files.readString(WORKFLOWS.resolve(workflow)));
        return List.of(yaml.split("(?m)^(?=      - )"));
    }

    private static int indexOfStepContaining(List<String> steps, String fragment) {
        return IntStream.range(0, steps.size()).filter(i -> steps.get(i).contains(fragment)).findFirst().orElse(-1);
    }

    private static String valueOf(String step, String key, String workflow) {
        Matcher value = Pattern.compile("(?m)^\\s+" + key + ":\\s*(\\S+)\\s*$").matcher(step);
        assertThat(value.find()).as("%s — 작업 공간 저장소를 받는 단계에 %s 가 있어야 한다(사양 파일 한 개만 받는다)", workflow, key).isTrue();
        return value.group(1);
    }

    /** `#` 로 시작하는 줄(YAML 주석)을 걷어 낸다. */
    private static String stripComments(String text) {
        return text.lines().filter(line -> !line.strip().startsWith("#")).collect(Collectors.joining("\n"));
    }
}
