package src.backend.global.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/**
 * <b>1건만 집어 오는 파생 쿼리</b>가 정렬 조항 없이 선언되는 것을 실패로 만든다.
 *
 * <p>{@code findTopBy…} · {@code findFirstBy…} 는 조건에 맞는 여러 행 중 <b>하나</b>를 돌려준다.
 * 어느 하나인지는 {@code OrderBy} 가 정한다 — 없으면 DB 가 편한 순서로 주고, 그 순서는 계약이 아니다.
 * {@code §2.9} 복구 코드 대조와 {@code §2.10} 가입 상태 조회가 <b>"가장 최근 1건"</b> 을 전제하는데,
 * 정렬이 빠지면 <b>이미 소비된 옛 코드</b>나 <b>옛 가입 신청</b>이 대신 나온다.
 *
 * <p>이 사고는 테스트로 잡히지 않는 형태다 — 행이 적은 로컬에서는 삽입 순서대로 돌아와 우연히
 * 맞는 답이 나오고, 행이 쌓인 뒤에야 어긋난다. 그래서 <b>동작이 아니라 선언</b>을 고정한다.
 *
 * <p>이 검사는 <b>삭제된 정렬 결정성 테스트의 대체물</b>이다(Phase 2 Task 3 우려 ①). 옛 테스트는
 * 데이터를 넣고 순서를 보는 방식이라 실패를 재현할 수단이 없어 산출물로 인정받지 못했다. 선언을 보는
 * 쪽은 {@code OrderBy} 를 지우는 것만으로 실패가 재현된다 — <b>재현 가능성이 이 교체의 이유다.</b>
 *
 * <p>{@code @Query} 가 붙은 메서드는 대상 밖이다 — 정렬이 메서드 이름이 아니라 JPQL 안에 있어
 * 이름만 봐서는 판정할 수단이 부재하다. 그쪽은 쿼리 문자열을 읽는 별도 축이 필요하다.
 */
class DerivedQueryDeterminismTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java/src/backend");

    /** 여러 행 중 하나만 집어 오는 Spring Data 접두사 — 이 이름을 쓰는 순간 "어느 하나" 를 정해야 한다. */
    private static final List<String> SINGLE_ROW_PREFIXES =
            List.of("findTop", "findFirst", "readTop", "readFirst", "queryTop", "queryFirst",
                    "getTop", "getFirst", "searchTop", "searchFirst");

    /**
     * 스캐너가 실제로 저장소를 보고 있는지 먼저 고정한다 — 대상이 0건이면 위반 0건은
     * "규칙이 지켜졌다" 가 아니라 <b>"아무것도 못 찾았다"</b> 는 뜻이고, 그 둘은 초록으로 구분되지 않는다.
     */
    @Test
    void 단건_파생_쿼리가_실제로_발견된다() {
        assertThat(singleRowDerivedQueries())
                .as("하나도 못 찾으면 아래 단언은 무엇도 보장하지 않는다 — 패키지 규약이나 접두사 목록이 어긋난 것")
                .isNotEmpty();
    }

    @Test
    void 단건_파생_쿼리는_정렬_조항을_갖는다() {
        List<String> violations = singleRowDerivedQueries().stream()
                .filter(method -> !method.getName().contains("OrderBy"))
                .map(method -> method.getDeclaringClass().getSimpleName() + "." + method.getName())
                .toList();

        assertThat(violations)
                .as("정렬이 없으면 여러 행 중 어느 것이 오는지 DB 가 정한다 — 옛 인증 코드·옛 가입 신청이 최신 대신 나온다")
                .isEmpty();
    }

    // ── 스캔 ──────────────────────────────────────────────────────────────

    /** {@code @Query} 없이 메서드 이름만으로 만들어지는 단건 조회 — 이름이 곧 쿼리인 것들. */
    private static List<Method> singleRowDerivedQueries() {
        return repositoryInterfaces().stream()
                .flatMap(repository -> Arrays.stream(repository.getDeclaredMethods()))
                .filter(method -> !method.isAnnotationPresent(Query.class))
                .filter(method -> SINGLE_ROW_PREFIXES.stream().anyMatch(method.getName()::startsWith))
                .toList();
    }

    private static List<Class<?>> repositoryInterfaces() {
        return classesUnder("repository").stream()
                .filter(Repository.class::isAssignableFrom)
                .toList();
    }

    /** {@code src/main/java} 아래 지정한 이름의 패키지에 있는 클래스를 소스 경로에서 유도해 적재한다. */
    private static List<Class<?>> classesUnder(String packageSegment) {
        try (Stream<Path> paths = Files.walk(SOURCE_ROOT)) {
            return paths.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.getParent().getFileName().toString().equals(packageSegment))
                    .map(DerivedQueryDeterminismTest::loadClass)
                    .filter(loaded -> loaded != null)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Class<?> loadClass(Path source) {
        String relative = SOURCE_ROOT.relativize(source).toString();
        String className = "src.backend." + relative.substring(0, relative.length() - ".java".length())
                .replace('/', '.');
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }
}
