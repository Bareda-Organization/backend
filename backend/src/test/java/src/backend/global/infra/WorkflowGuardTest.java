package src.backend.global.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 백엔드 시험을 돌리는 두 워크플로({@code ci.yml} · {@code deploy-backend.yml})가 같은 입력을 준비하는지 본다 — BR-304 (R10-01).
 *
 * <p>{@code ErrorCodeSpecParityTest} 는 사양 파일({@code API_SPEC.md})을 읽는데 그 파일은 작업 공간 저장소에 있다. 러너는 backend 저장소 하나만
 * 받으므로 워크플로가 사양 파일을 따로 받아 {@code API_SPEC_PATH} 로 알려 줘야 한다. 한쪽만 고치면 다른 쪽 시험이 항상 실패한다 — 배포 워크플로가
 * 그랬다(저장소 분리 때 {@code ci.yml} 만 고쳐서, 첫 배포에서야 드러날 상태). 공개 저장소라 Actions 로그가 공개이므로 {@code -PciQuiet} 도 같이 본다.
 *
 * <p>{@code ci.yml} 의 경로 거르기가 시험이 읽는 저장소 루트 입력을 덮는지도 본다 — BR-327 (R10-03). 인프라 지침 시험은 {@code backend/} 안에 있지만
 * 읽는 파일은 {@code infra/} · {@code docker-compose*.yml} · {@code .github/} 에 있어, 거르기가 {@code backend/**} 만 보면 그 파일만 바꾼 PR 은
 * 시험이 통째로 건너뛰어진 채 초록으로 보인다.
 *
 * <p>제3자 액션이 커밋 SHA 로 고정됐는지도 본다 — BR-371 (R10-12). 주 버전 태그({@code @v4})는 저장소 소유자가 옮길 수 있어, 탈취되면 그 작업이 임의
 * 코드를 실행한다. 공급자(GitHub · AWS) 액션은 태그로 둔다 — Dependabot {@code github-actions} 가 SHA 도 같이 올린다.
 *
 * <p>파일 텍스트만 읽는다. 주석 줄은 걷어 내고 본다 — 주석이 옛 설정을 설명해도 통과하지 않게.
 */
class WorkflowGuardTest {

    private static final Path WORKFLOWS = Path.of("..", ".github", "workflows");

    private static final String WORKSPACE_CHECKOUT = "repository: ${{ github.repository_owner }}/workspace";

    /** 시험 소스에서 저장소 루트 입력을 가리키는 문자열 — {@code "../infra/…"} · {@code "..", "infra"} · {@code read("docker-compose.prod.yml")} 꼴의 첫 경로. */
    private static final Pattern REPOSITORY_INPUT = Pattern.compile(
            "\"(?:\\.\\.(?:/|\",\\s*\"))?(infra|\\.github|docker-compose[\\w.-]*\\.yml)(?=[/\"])");

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

    @Test
    @DisplayName("ci.yml 의 경로 거르기가 시험이 읽는 저장소 루트 입력(infra · docker-compose*.yml · .github)을 덮는다 — 인프라만 바뀐 PR 도 시험이 돈다")
    void ciPathFilterCoversEveryRepositoryInputTestsRead() throws IOException {
        Set<String> required = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src", "test", "java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher input = REPOSITORY_INPUT.matcher(Files.readString(file));
                while (input.find()) {
                    required.add(input.group(1).startsWith("docker-compose") ? "docker-compose*.yml" : input.group(1) + "/**");
                }
            }
        }
        assertThat(required).as("시험 소스에서 찾은 저장소 루트 입력 — 비어 있으면 이 검사가 아무것도 못 본다").contains("infra/**", "docker-compose*.yml");

        Matcher listed = Pattern.compile("(?m)^\\s+- '([^']+)'\\s*$").matcher(stripComments(Files.readString(WORKFLOWS.resolve("ci.yml"))));
        Set<String> filter = new TreeSet<>();
        while (listed.find()) {
            filter.add(listed.group(1));
        }
        assertThat(filter).as("ci.yml 의 changes 작업 거르기 목록 — 시험이 읽는 입력(%s)을 전부 포함해야 그 파일만 바꾼 PR 에서도 시험이 돈다", required)
                .containsAll(required);
    }

    @Test
    @DisplayName("ci.yml 이 경보 규칙 시험(promtool test rules alerts.test.yml)을 운영 Prometheus 와 같은 이미지로 돈다 — 식을 고쳐도 시험이 안 도는 상태를 막는다(BR-329)")
    void ciRunsPromtoolOnTheAlertRulesWithTheProductionPrometheusImage() throws IOException {
        Matcher image = Pattern.compile("(?m)^\\s*image:\\s*(prom/prometheus:\\S+)")
                .matcher(stripComments(Files.readString(Path.of("..", "docker-compose.prod.yml"))));
        assertThat(image.find()).as("운영 compose 에 Prometheus 이미지가 있어야 한다").isTrue();

        String ci = stripComments(Files.readString(WORKFLOWS.resolve("ci.yml")));
        assertThat(ci).as("ci.yml — 운영 Prometheus(%s)와 같은 이미지의 promtool 로 규칙 시험을 돈다(문법·함수가 버전마다 다르다)", image.group(1))
                .contains(image.group(1)).contains("promtool").contains("test rules").contains("alerts.test.yml");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backendTestWorkflows")
    @DisplayName("공급자(actions · aws-actions) 밖의 제3자 액션은 40자 커밋 SHA 로 고정한다 — 옮길 수 있는 태그로 두지 않는다")
    void thirdPartyActionsArePinnedToCommitSha(String workflow) throws IOException {
        Matcher use = Pattern.compile("(?m)^\\s*(?:- )?uses:\\s*([^\\s#]+)").matcher(stripComments(Files.readString(WORKFLOWS.resolve(workflow))));
        while (use.find()) {
            String action = use.group(1);
            if (!action.startsWith("actions/") && !action.startsWith("aws-actions/")) {
                assertThat(action).as("%s — 제3자 액션은 `<소유자>/<이름>@<40자 SHA> # <버전 태그>` 로 고정한다", workflow).matches(".+@[0-9a-f]{40}");
            }
        }
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
