package src.backend.global.security.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * 학원 대조가 <b>판정 지점 한 곳({@code global/security/access/})에만</b> 있는지 소스 수준에서 고정한다.
 *
 * <p>필요한 이유는 실제 사고 2건이다 — 옛 {@code global/tenant/TenantGuard} 와
 * {@code StompAuthChannelInterceptor} 가 각자 학원을 대조했고, <b>둘 다 같은 결함</b>(메인 관리자를
 * 거부)을 가졌다. 한쪽을 고쳐도 다른 쪽은 "여긴 왜 다르지" 라는 물음 없이 남는다.
 *
 * <p>⚠ <b>이 검사는 "틀린 대조" 를 잡지 "빠진 대조" 를 잡지 못한다.</b> 저장소 장치
 * ({@link AcademyScopeRepositoryConventionTest})가 누락까지 잡는 것은 쿼리가 <b>선언돼 열거 가능</b>하기
 * 때문인데, 인라인 대조는 선언 지점이 없는 임의 표현식이라 텍스트에는 <b>있는 것의 모양</b>만 남는다.
 * 있어야 하는데 없는 대조는 원리상 관측 대상 밖이다 — 그것을 막는 것은 자원을 꺼내는 경로를 저장소
 * 계층으로 모으는 쪽(Task 5 본체)이고 이 검사가 아니다.
 *
 * <p>정밀도의 한계도 하나 있다 — 텍스트만으로는 {@code AuthUser.academyId()} 와 요청 DTO 의 동명
 * 접근자를 구별하지 못한다. 그래서 규칙을 <b>넓게</b> 잡고(어떤 {@code academyId()} 든 비교·대입이면
 * 위반), 정당한 것은 {@link #ALLOWED} 에 근거와 함께 적는다. 좁게 잡아 놓치는 편보다 낫다.
 */
class AcademyScopeSingleJudgmentPointTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java/src/backend");

    /** 학원 대조가 있어도 되는 유일한 곳 — 판정 지점 자신. */
    private static final Path JUDGMENT_POINT = SOURCE_ROOT.resolve("global/security/access");

    /**
     * {@code 파일명:근거} 형태의 예외 목록. 비어 있는 것이 정상이며, 채울 때는 <b>왜 판정 지점을 거칠 수
     * 없는지</b>를 적는다. 근거 없이 이름만 적으면 그 파일에 대조가 하나 더 늘어도 가려진다.
     */
    private static final List<String> ALLOWED = List.of();

    /** 학원 식별자를 다른 값과 견주는 형태 — 이것이 곧 "판정" 이다. */
    private static final List<Pattern> COMPARISON_SHAPES = List.of(
            Pattern.compile("\\.academyId\\(\\)\\s*[!=]="),
            Pattern.compile("[!=]=\\s*[\\w.]*\\.academyId\\(\\)"),
            Pattern.compile("\\.academyId\\(\\)\\.equals\\("),
            Pattern.compile("\\.equals\\(\\s*[\\w.]*\\.academyId\\(\\)\\s*\\)"));

    /**
     * 지역변수로 옮겨 담은 뒤 비교하는 우회 — 현재 0건이라 지금 막는 비용이 부재하다.
     * 나중에 생기면 위 비교 형태 검사가 통째로 무력해진다.
     */
    private static final Pattern BINDING_SHAPE =
            Pattern.compile("=\\s*[\\w.]*\\.academyId\\(\\)\\s*;");

    @Test
    void 판정_지점_패키지가_존재한다() {
        assertThat(JUDGMENT_POINT).as("경로가 어긋나면 아래 하한 단언이 먼저 깨져 거짓 초록을 막는다").isDirectory();
    }

    /**
     * 스캐너가 실제로 코드를 보고 있는지 먼저 고정한다 — 판정 지점 자신의 대조가 잡히지 않으면
     * 위반 0건은 "규칙이 지켜졌다" 가 아니라 "아무것도 못 찾았다" 는 뜻이다.
     */
    @Test
    void 판정_지점_안에서_학원_대조가_실제로_발견된다() {
        assertThat(comparisonsIn(JUDGMENT_POINT))
                .as("AcademyScope 의 대조조차 못 찾으면 이 스캐너는 무엇도 보장하지 않는다")
                .isNotEmpty();
    }

    @Test
    void 판정_지점_밖에는_학원_대조가_없다() {
        List<String> violations = comparisonsOutsideJudgmentPoint(COMPARISON_SHAPES);

        assertThat(violations)
                .as("학원 대조는 AcademyScope 가 판정한다 — 직접 견주면 메인 관리자 예외 같은 규칙이 갈린다")
                .isEmpty();
    }

    @Test
    void 판정_지점_밖에서_학원_식별자를_지역변수로_옮겨_담지_않는다() {
        List<String> violations = comparisonsOutsideJudgmentPoint(List.of(BINDING_SHAPE));

        assertThat(violations)
                .as("옮겨 담은 뒤 비교하면 위 검사를 그대로 지나간다 — 값을 그대로 판정 지점에 넘긴다")
                .isEmpty();
    }

    // ── 스캔 ──────────────────────────────────────────────────────────────

    private static List<String> comparisonsOutsideJudgmentPoint(List<Pattern> shapes) {
        List<String> hits = new ArrayList<>();
        for (Path source : javaSources(SOURCE_ROOT)) {
            if (source.startsWith(JUDGMENT_POINT) || ALLOWED.contains(source.getFileName().toString())) {
                continue;
            }
            hits.addAll(matches(source, shapes));
        }
        return hits;
    }

    private static List<String> comparisonsIn(Path root) {
        List<String> hits = new ArrayList<>();
        for (Path source : javaSources(root)) {
            hits.addAll(matches(source, COMPARISON_SHAPES));
        }
        return hits;
    }

    /** 위반을 {@code 파일명:줄번호 본문} 으로 적는다 — 어디를 고쳐야 하는지가 실패 메시지에 있어야 한다. */
    private static List<String> matches(Path source, List<Pattern> shapes) {
        List<String> hits = new ArrayList<>();
        List<String> lines = readLines(source);
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            for (Pattern shape : shapes) {
                Matcher matcher = shape.matcher(line);
                if (matcher.find()) {
                    hits.add(source.getFileName() + ":" + (index + 1) + " " + line.trim());
                    break;
                }
            }
        }
        return hits;
    }

    private static List<Path> javaSources(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(path -> path.toString().endsWith(".java")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> readLines(Path path) {
        try {
            return Files.readAllLines(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
