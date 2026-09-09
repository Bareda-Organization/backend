package src.backend.student.geocoding;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Transactional;

import src.backend.student.geocoding.spec.GeocodingClient;

/**
 * 지오코딩 호출이 트랜잭션 안으로 들어가는 것을 <b>실패로 만드는</b> 회귀 방지 테스트(§7 규칙 16).
 *
 * <p>이 규칙은 단언으로 잡히지 않는다. 테스트 전체 묶음은 결정론적 스텁으로 돌아 즉시 반환하므로,
 * 검증을 트랜잭션 안으로 옮겨 놓아도 <b>모든 기능 테스트가 그대로 초록</b>이다 — 드러나는 것은 실
 * 네이버가 느려진 운영에서 커넥션과 행 잠금이 응답 시간만큼 붙들릴 때뿐이다. 그래서
 * "규칙을 지켰는가" 가 아니라 <b>"어긴 클래스가 있는가"</b> 를 기계가 세게 한다.
 *
 * <p><b>검사 대상은 지오코딩 포트에 닿는 클래스뿐이다.</b> "서비스에 트랜잭션을 붙이지 마라" 같은
 * 전역 규칙으로 넓히지 않는다 — 다른 Phase 의 서비스는 트랜잭션 경계가 맞는 자리이고, 규칙이 자기
 * 근거를 넘어서면 다음 사람이 근거를 확인하지 않고 꺼 버린다.
 *
 * <p>닿는지는 <b>주입 필드를 따라</b> 판정한다. {@link GeocodingClient} 를 직접 들고 있는 클래스뿐
 * 아니라 그것을 든 빈을 든 클래스까지 포함한다 — 실제로 위험한 자리가 후자다. 지오코딩을 직접
 * 부르는 {@code AddressVerification} 에 트랜잭션을 붙이는 사람은 드물고, "서비스 메서드에는
 * {@code @Transactional} 을 붙인다" 는 관례를 따라 오케스트레이터
 * ({@code WeeklyAddressCommandService})에 두 줄을 더하는 것이 흔한 경로다.
 *
 * <p><b>이 장치가 보지 않는 것</b> — 필드가 아닌 경로로 포트를 얻는 형태(메서드 인자 ·
 * {@code ApplicationContext} 조회 · 컬렉션 필드)는 잡지 못한다. 그런 형태를 쓸 이유가 현재
 * 부재하고, 넣으면 검사가 소스 텍스트 추측에 기대게 된다.
 */
class GeocodingTransactionConventionTest {

    /**
     * 스캔 기준 경로 — 상대 경로라 <b>작업 디렉토리가 {@code backend/} 일 때만</b> 성립한다.
     * 어긋나면 수집이 공집합이 되어 규칙이 지켜진 것과 구별되지 않으므로 실재를 먼저 단언한다.
     */
    private static final Path SOURCE_ROOT = Path.of("src/main/java/src/backend");

    /**
     * 지오코딩에 닿는 클래스로 <b>반드시</b> 잡혀야 하는 것들 — 수집이 통째로 실패하는 것을 막는
     * 하한이다. 이 목록이 비면 아래 검사는 "위반이 없다" 와 "아무것도 못 봤다" 를 구별하지 못한다.
     */
    private static final Set<String> MUST_BE_DETECTED = Set.of(
            "AddressVerification",
            "WeeklyAddressCommandService");

    @Test
    void 소스_루트가_존재한다() {
        assertThat(SOURCE_ROOT)
                .as("작업 디렉토리가 backend/ 가 아니면 아래 스캔이 전부 공집합으로 초록이 된다")
                .isDirectory();
    }

    /**
     * 수집의 하한 — 지오코딩을 직접 부르는 클래스와 그것을 부르는 오케스트레이터가 둘 다 잡혀야 한다.
     * 하나만 잡히면 <b>간접 경로가 보이지 않는 상태</b>이고, 그때가 정확히 이 규칙이 깨지는 형태다.
     */
    @Test
    void 지오코딩에_닿는_클래스에_직접_호출자와_간접_호출자가_함께_잡힌다() {
        List<String> detected = classesReachingGeocodingPort().stream().map(Class::getSimpleName).toList();

        assertThat(detected)
                .as("주입 필드를 따라가는 수집이 실패했다 — 이 상태에서는 아래 검사가 아무것도 보지 않는다")
                .containsAll(MUST_BE_DETECTED);
    }

    /**
     * 지오코딩에 닿는 클래스는 <b>트랜잭션 경계가 될 수 없다</b> — 클래스에도, 어느 메서드에도
     * {@code @Transactional} 이 없어야 한다.
     *
     * <p>메서드까지 보는 이유는 클래스 레벨만 검사하면 문제의 메서드 하나에만 붙이는 형태가 그대로
     * 통과하기 때문이다. 그 형태가 오히려 흔하다 — 저장 메서드에만 붙이려다 그 메서드가 검증까지
     * 함께 부르고 있는 경우다.
     */
    @Test
    void 지오코딩에_닿는_클래스는_트랜잭션_경계가_아니다() {
        List<String> violations = new ArrayList<>();
        for (Class<?> reaching : classesReachingGeocodingPort()) {
            if (AnnotatedElementUtils.hasAnnotation(reaching, Transactional.class)) {
                violations.add(reaching.getSimpleName() + " (클래스 레벨)");
            }
            for (Method method : reaching.getDeclaredMethods()) {
                if (AnnotatedElementUtils.hasAnnotation(method, Transactional.class)) {
                    violations.add(reaching.getSimpleName() + "#" + method.getName());
                }
            }
        }

        assertThat(violations)
                .as("지오코딩 호출이 트랜잭션 안으로 들어갔다 — 외부 응답 시간만큼 커넥션과 행 잠금이 붙들린다."
                        + " 검증과 저장을 다른 빈으로 갈라 저장 쪽에만 트랜잭션을 둔다(§7 규칙 16)")
                .isEmpty();
    }

    // ── 검사 대상 수집 ─────────────────────────────────────────────────────

    /**
     * 주입 필드를 따라 {@link GeocodingClient} 에 닿는 클래스 전부 — 포트 자신과 그 구현체는 뺀다.
     * 어댑터가 자기 자신을 참조하는 것은 "트랜잭션 안에서 외부를 부른다" 와 무관하다.
     */
    private static Set<Class<?>> classesReachingGeocodingPort() {
        List<Class<?>> all = mainClasses();
        Set<Class<?>> reaching = new LinkedHashSet<>();
        for (Class<?> candidate : all) {
            if (GeocodingClient.class.isAssignableFrom(candidate)) {
                continue;
            }
            if (reachesPort(candidate, new LinkedHashSet<>())) {
                reaching.add(candidate);
            }
        }
        return reaching;
    }

    /** 순환 참조가 있어도 멈추도록 방문 집합을 들고 내려간다 — 없으면 스택이 넘친다. */
    private static boolean reachesPort(Class<?> type, Set<Class<?>> visiting) {
        if (!visiting.add(type)) {
            return false;
        }
        for (Field field : type.getDeclaredFields()) {
            Class<?> fieldType = field.getType();
            if (GeocodingClient.class.isAssignableFrom(fieldType)) {
                return true;
            }
            if (fieldType.getName().startsWith("src.backend.") && reachesPort(fieldType, visiting)) {
                return true;
            }
        }
        return false;
    }

    private static List<Class<?>> mainClasses() {
        try (Stream<Path> paths = Files.walk(SOURCE_ROOT)) {
            return paths.filter(path -> path.toString().endsWith(".java"))
                    .map(GeocodingTransactionConventionTest::loadClass)
                    .filter(loaded -> loaded != null)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 초기화 없이 적재한다 — 정적 초기화가 도는 클래스가 섞이면 스캔이 앱을 부분 기동하는 꼴이 된다. */
    private static Class<?> loadClass(Path source) {
        String relative = SOURCE_ROOT.relativize(source).toString();
        String className = "src.backend." + relative.substring(0, relative.length() - ".java".length())
                .replace('/', '.');
        try {
            return Class.forName(className, false, GeocodingTransactionConventionTest.class.getClassLoader());
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            return null;
        }
    }
}
