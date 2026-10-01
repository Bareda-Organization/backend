package src.backend.global.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * {@link ErrorCode} 열거 값 전부가 {@code docs/API_SPEC.md} §8(에러 코드 사전) 표에 있고, 그 반대도 같다(R46-LATERBE, Ruling 677).
 * R46-FIXTX 가 {@code SERVER_BUSY} 를 만들고 §1.11 에만 적어 §8 에는 행이 없었다 — 웹 쪽 시험은 "§8 ⊆ 웹 목록" 한 방향만 봐서 못 잡았다.
 * 사양이 에러 코드의 단일 소스이므로 열거 쪽이 사전보다 앞서가면(사전에 없는 코드를 클라이언트가 처리할 방법이 없다) 이 시험이 실패한다.
 *
 * <p>사전은 표의 첫 열 {@code `CODE`} 로 읽는다. 취소선({@code ~~`CODE`~~}) 행은 폐지된 코드라 세지 않는다 — 폐지 코드가 열거에 남아
 * 있으면 사전에 없는 것과 같다.
 */
class ErrorCodeSpecParityTest {

    private static final Path API_SPEC = Path.of("..", "docs", "API_SPEC.md");

    private static final String SECTION_START = "## 8. 에러 코드 사전";

    private static final String SECTION_END = "## 9. enum 사전";

    /** 취소선이 없는 행의 코드 — {@code | `CODE` | 401 |} . */
    private static final Pattern LIVE_ROW = Pattern.compile("^\\| `([A-Z][A-Z0-9_]+)` \\|", Pattern.MULTILINE);

    @Test
    void ErrorCode_열거_값은_전부_API_SPEC_8_표에_있다() throws IOException {
        Set<String> documented = 사전의_코드();
        Set<String> declared = new TreeSet<>();
        Arrays.stream(ErrorCode.values()).forEach(code -> declared.add(code.name()));

        assertThat(documented).as("사전을 읽지 못했거나 범위가 어긋났다 — 코드가 수십 개는 있어야 한다").hasSizeGreaterThan(50);
        Set<String> missing = new TreeSet<>(declared);
        missing.removeAll(documented);
        assertThat(missing).as("ErrorCode 에는 있고 API_SPEC §8 표에는 없는 코드 — 사전에 행을 더한다").isEmpty();
    }

    @Test
    void API_SPEC_8_표의_코드는_전부_ErrorCode_에_있다() throws IOException {
        Set<String> declared = new TreeSet<>();
        Arrays.stream(ErrorCode.values()).forEach(code -> declared.add(code.name()));

        Set<String> orphan = new TreeSet<>(사전의_코드());
        orphan.removeAll(declared);
        assertThat(orphan).as("API_SPEC §8 표에는 있고 ErrorCode 에는 없는 코드 — 폐지했으면 취소선으로, 아니면 열거에 더한다").isEmpty();
    }

    private static Set<String> 사전의_코드() throws IOException {
        String spec = Files.readString(API_SPEC);
        int start = spec.indexOf(SECTION_START);
        int end = spec.indexOf(SECTION_END);
        assertThat(start).as("API_SPEC 에서 '%s' 절을 찾지 못했다", SECTION_START).isNotNegative();
        assertThat(end).as("API_SPEC 에서 '%s' 절을 찾지 못했다", SECTION_END).isGreaterThan(start);

        Set<String> codes = new TreeSet<>();
        Matcher matcher = LIVE_ROW.matcher(spec.substring(start, end));
        while (matcher.find()) {
            codes.add(matcher.group(1));
        }
        return codes;
    }
}
