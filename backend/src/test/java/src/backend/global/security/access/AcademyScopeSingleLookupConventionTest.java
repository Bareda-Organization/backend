package src.backend.global.security.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * 학원 소속 엔티티의 저장소를 <b>id 한 개로 꺼내는 호출</b>({@code findById} · {@code getReferenceById} ·
 * {@code existsById} · {@code findAllById} · {@code deleteById})을 검토 목록에 묶는다.
 *
 * <p>이런 호출은 id 만으로 행을 읽어 학원 조건이 붙을 자리가 없다. 그 뒤에 {@link AcademyScope#assertAccessible} 가
 * 오는지는 호출 <b>순서</b>라 소스 텍스트로 가를 수 없으므로, 호출 지점마다 사람이 검토한 판정을
 * {@code authz/single-lookup-baseline.tsv} 에 적고, 목록에 없는 새 호출 지점이 생기면 이 테스트가 실패한다.
 *
 * <p>호출 지점의 식별자는 <b>파일 · 둘러싼 메서드 · 호출 문장</b>이다(줄 번호가 아니라서 위아래 줄이 바뀌어도 유지된다).
 * 판정은 네 가지다 — {@code scoped-after}(읽은 뒤 학원 범위 확인이 따른다) · {@code keyed-from-scoped}(id 가 학원
 * 범위 안에서 이미 읽은 행이나 토큰의 계정에서 나온다) · {@code platform-scope}(메인 관리자나 스케줄러처럼 학원을
 * 가로지르는 경로) · {@code fixed}(학원 조건이 붙은 조회로 바꿨다).
 */
class AcademyScopeSingleLookupConventionTest {

    private static final Path BASELINE = Path.of("src/test/resources/authz/single-lookup-baseline.tsv");

    private static final Set<String> VERDICTS = Set.of("scoped-after", "keyed-from-scoped", "platform-scope", "fixed");

    /** 부모 경유로 학원에 닿는 자식 중 학원 소속으로 다루는 아홉 테이블 — 엔티티에 {@code academyId} 가 생기기 전에도 같은 대상이다. */
    private static final Set<String> CHILD_TABLES = Set.of("run_rider", "route_stop", "run_stop", "assignment",
            "guardian_student", "weekly_address", "run_forced_addition", "boarding_intent", "run_transfer");

    private static final String LOOKUPS = "findById|getReferenceById|existsById|findAllById|deleteById";

    private static final Pattern METHOD_HEAD = Pattern.compile(
            "(?m)^ {4}(?! )(?:@\\w+(?:\\([^)]*\\))?\\s+)*(?:(?:public|private|protected|static|final|synchronized) )*"
                    + "[\\w<>\\[\\],?. ]*?(\\w+)\\(");

    @Test
    void 소스_루트가_존재한다() {
        assertThat(AcademyScopeScan.SOURCE_ROOT).as("작업 디렉토리가 backend/ 가 아니면 스캔이 공집합이 된다").isDirectory();
    }

    @Test
    void 학원_소속_저장소가_수집된다() {
        assertThat(ownedRepositories())
                .as("저장소 수집이 비면 아래 검사가 전부 공집합으로 통과한다")
                .hasSizeGreaterThanOrEqualTo(10);
    }

    @Test
    void id_단건_호출_지점은_모두_검토_목록에_있다() {
        List<String> baselineKeys = baselineLines().stream().map(line -> keyOf(line.split("\t", -1))).sorted().toList();
        List<String> scanned = scanCallSites().stream().sorted().toList();

        assertThat(scanned)
                .as("검토 목록(%s)에 없는 호출 지점이 있다 — 학원 범위 확인이 따르는지 읽고 판정을 적는다.%n새 지점 형식: 파일<TAB>메서드<TAB>호출 문장<TAB>판정<TAB>근거",
                        BASELINE)
                .containsExactlyInAnyOrderElementsOf(baselineKeys);
    }

    @Test
    void 검토_목록의_판정과_근거가_채워져_있다() {
        for (String line : baselineLines()) {
            String[] cells = line.split("\t", -1);
            assertThat(cells).as("열 5개: 파일 메서드 호출 문장 판정 근거 — %s", line).hasSize(5);
            assertThat(VERDICTS).as("판정 값 — %s", line).contains(cells[3]);
            assertThat(cells[4].strip()).as("근거 — %s", line).isNotEmpty();
        }
    }

    private static String keyOf(String[] cells) {
        return cells[0] + "\t" + cells[1] + "\t" + cells[2];
    }

    private static List<String> baselineLines() {
        try {
            return Files.readAllLines(BASELINE).stream().filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 엔티티가 {@code academyId} 를 갖거나 아홉 자식 테이블인 저장소. */
    private static List<Class<?>> ownedRepositories() {
        return AcademyScopeScan.repositoryInterfaces().stream().filter(repository -> {
            Class<?> entity = AcademyScopeScan.entityTypeOf(repository);
            return entity != null && (AcademyScopeScan.hasAcademyIdField(entity)
                    || CHILD_TABLES.contains(AcademyScopeScan.tableName(entity)));
        }).toList();
    }

    private static List<String> scanCallSites() {
        List<String> found = new ArrayList<>();
        for (Path path : AcademyScopeScan.mainSources()) {
            String source = blankComments(AcademyScopeScan.read(path));
            for (Class<?> repository : ownedRepositories()) {
                for (String field : fieldNamesOf(source, repository.getSimpleName())) {
                    collect(path, source, field, found);
                }
            }
        }
        return found;
    }

    /** 주석 안의 {@code repository.findById} 언급이 호출로 잡히지 않도록 주석을 같은 길이의 공백으로 바꾼다. */
    private static String blankComments(String source) {
        Matcher comment = Pattern.compile("/\\*.*?\\*/|//[^\n]*", Pattern.DOTALL).matcher(source);
        StringBuilder blanked = new StringBuilder(source);
        while (comment.find()) {
            for (int i = comment.start(); i < comment.end(); i++) {
                if (blanked.charAt(i) != '\n') {
                    blanked.setCharAt(i, ' ');
                }
            }
        }
        return blanked.toString();
    }

    /** 필드와 생성자 인자가 같은 이름으로 두 번 잡히므로 집합으로 모은다. */
    private static Set<String> fieldNamesOf(String source, String typeName) {
        Matcher matcher = Pattern.compile("\\b" + typeName + "\\s+(\\w+)\\s*[;=),]").matcher(source);
        Set<String> names = new LinkedHashSet<>();
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static void collect(Path path, String source, String field, List<String> found) {
        Matcher call = Pattern.compile("\\b" + field + "\\s*(?:\\.\\s*|::\\s*)(?:" + LOOKUPS + ")\\b").matcher(source);
        while (call.find()) {
            found.add(AcademyScopeScan.SOURCE_ROOT.relativize(path) + "\t" + enclosingMethod(source, call.start())
                    + "\t" + callText(source, call.start(), call.end()));
        }
    }

    private static String enclosingMethod(String source, int offset) {
        Matcher head = METHOD_HEAD.matcher(source.substring(0, offset));
        String name = "<class>";
        while (head.find()) {
            name = head.group(1);
        }
        return name;
    }

    /** 호출 문장 — 메서드 참조면 그대로, 호출이면 여는 괄호부터 짝이 맞는 닫는 괄호까지(공백은 하나로 줄인다). */
    private static String callText(String source, int start, int end) {
        int cursor = end;
        if (cursor < source.length() && source.charAt(cursor) == '(') {
            int depth = 0;
            do {
                char c = source.charAt(cursor++);
                depth += c == '(' ? 1 : c == ')' ? -1 : 0;
            } while (depth > 0 && cursor < source.length());
            end = cursor;
        }
        return source.substring(start, end).replaceAll("\\s+", " ").replace("( ", "(").replace(" )", ")")
                .replace(" .", ".");
    }
}
