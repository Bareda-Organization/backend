package src.backend.global.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 백엔드 시험을 돌리는 두 워크플로({@code ci.yml} · {@code deploy-backend.yml})의 제3자 액션이 커밋 SHA 로 고정됐는지 본다 — BR-371.
 *
 * <p>주 버전 태그({@code @v4})는 저장소 소유자가 옮길 수 있어, 탈취되면 그 작업이 임의 코드를 실행한다. 공급자(GitHub · AWS) 액션은 태그로 둔다 —
 * Dependabot {@code github-actions} 가 SHA 도 같이 올린다. 주석 줄은 걷어 내고 본다 — 주석이 옛 설정을 설명해도 통과하지 않게.
 */
class WorkflowGuardTest {

    private static final Path WORKFLOWS = Path.of("..", ".github", "workflows");

    static Stream<String> backendTestWorkflows() {
        return Stream.of("ci.yml", "deploy-backend.yml");
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

    /** `#` 로 시작하는 줄(YAML 주석)을 걷어 낸다. */
    private static String stripComments(String text) {
        return text.lines().filter(line -> !line.strip().startsWith("#")).collect(Collectors.joining("\n"));
    }
}
