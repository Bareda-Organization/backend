package src.backend.run.navigation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * (RUN-08) 상한 4 가 {@code KakaoNavProvider} 한 곳에만 존재하는지 소스 수준에서 고정한다(목표 22,
 * Ruling 201·204, {@code p9-goal-table.md} §7 규칙 12).
 *
 * <p>목표 표의 검사 명령은 {@code grep -rn '\b4\b' run/navigation/} 이지만, 이 모듈의 거의 모든 파일이
 * 자연어 주석으로 {@code API_SPEC §4.16} 을 인용한다 — 그 문자열의 "4" 도 리터럴 경계({@code \b4\b})를
 * 그대로 만족해 문자 그대로 실행하면 이 모듈이 결함 없이도 항상 실패한다(실측 확인됨). 이 시험은
 * <b>주석을 제거한 코드 본문</b>에서만 경계를 재는 것으로 목표의 의도(자르기 상한이 여러 곳에 흩어지는가)를
 * 그대로 지키면서 문서 인용을 오탐에서 뺀다.
 */
class NavigationCapConventionTest {

    private static final Path MODULE_ROOT = Path.of("src/main/java/src/backend/run/navigation");

    private static final String CAP_HOLDER_FILE = "KakaoNavProvider.java";

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    private static final Pattern LINE_COMMENT = Pattern.compile("//.*");

    private static final Pattern STANDALONE_FOUR = Pattern.compile("\\b4\\b");

    @Test
    void 상한_리터럴_4는_KakaoNavProvider_밖의_코드에_존재하지_않는다() throws IOException {
        try (Stream<Path> files = Files.walk(MODULE_ROOT)) {
            List<Path> javaFiles = files.filter(p -> p.toString().endsWith(".java")).toList();
            assertThat(javaFiles).as("스캔 대상 자체가 없으면 이 시험은 항상 통과해 아무것도 검사하지 못한다")
                    .isNotEmpty();

            for (Path file : javaFiles) {
                String code = 주석을_제거한다(Files.readString(file));
                boolean hasLiteral = STANDALONE_FOUR.matcher(code).find();
                if (file.getFileName().toString().equals(CAP_HOLDER_FILE)) {
                    assertThat(hasLiteral).as(CAP_HOLDER_FILE + " 는 상한 리터럴을 실제로 갖고 있어야 한다 — 없으면"
                            + " 이 시험이 통과해도 상한이 어디에도 없는 상태와 구별되지 않는다").isTrue();
                } else {
                    assertThat(hasLiteral).as(file + " 에 상한을 뜻하는 리터럴 4 가 있으면 안 된다 — 자르기 개수가"
                            + " 이 파일에도 흩어져 있다는 뜻이고, 티맵으로 바꿀 때 이 자리가 잘못된 길이로 자른다")
                            .isFalse();
                }
            }
        }
    }

    @Test
    void NavProvider_포트는_maxStops를_노출하고_구현체는_1개뿐이다() throws IOException {
        String portSource = Files.readString(MODULE_ROOT.resolve("spec/NavProvider.java"));
        assertThat(portSource).as("공급자를 바꿔도 상한을 묻는 창구는 하나여야 한다").contains("maxStops(");

        try (Stream<Path> files = Files.walk(MODULE_ROOT)) {
            long implementationCount = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            return Files.readString(p).contains("implements NavProvider");
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .count();
            assertThat(implementationCount).as("MVP 는 카카오내비 단독이다 — 티맵 구현체를 함께 두지 않는다")
                    .isEqualTo(1);
        }
    }

    private String 주석을_제거한다(String source) {
        String withoutBlockComments = BLOCK_COMMENT.matcher(source).replaceAll("");
        return LINE_COMMENT.matcher(withoutBlockComments).replaceAll("");
    }
}
