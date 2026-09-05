package src.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * {@code @Scheduled} 가 붙은 메서드에 {@code @SchedulerLock} 이 함께 붙어 있는지 소스 수준에서
 * 고정하는 회귀 방지 시험(Phase 11 마무리 라운드) — {@code ControllerAuthorizationConventionTest}
 * 와 같은 관례(면제 목록에 근거를 함께 적는다)를 그대로 따른다.
 *
 * <p>같은 형태의 누락이 이 저장소에서 반복됐다 — 규약 시험이 좌석 단독 실행에서는 걸리지 않고
 * 병합 후 전체 실행에서야 드러난 전례가 있다({@code ControllerAuthorizationConventionTest} 의
 * Phase 10 T1·Phase 11 T3 항목 참고). 이 시험도 새 스케줄러를 추가하는 좌석이 이 파일이 같은
 * 모듈에 있는지 모른 채 넘어갈 수 있으므로, 병합 뒤 전체 실행에서 이 클래스가 실제로 도는지 매번
 * 확인한다.
 *
 * <p><b>텍스트 스캔이 아니라 리플렉션으로 실제 애너테이션을 확인한다.</b> 이 저장소는 자바독에
 * {@code {@code @Scheduled}} 처럼 애너테이션 이름을 그대로 인용하는 관례가 있어(예:
 * {@code SchedulerHealthMetrics}), 문자열 검색은 그 인용까지 스케줄 메서드로 잘못 센다
 * (Phase 11 T4 가 조율자의 {@code grep} 오독을 실측으로 정정한 사례). {@link Class#getDeclaredMethods()}
 * 와 {@link Method#getAnnotation(Class)} 는 컴파일된 실제 애너테이션만 본다.
 */
class SchedulerLockConventionTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java/src/backend");

    /**
     * {@code 클래스명#메서드명} 형태로 적는 예외 목록 — {@code @SchedulerLock} 을 의도적으로 붙이지
     * 않는 {@code @Scheduled} 메서드. 항목마다 근거를 남긴다. 근거 없이 이름만 추가하면, 같은
     * 클래스에 락 없는 메서드가 하나 더 늘어도 이 목록이 통째로 가려 회귀를 못 잡는다.
     *
     * <p><b>{@code RunConfirmationScheduler#confirmDueRuns}.</b> 근거: {@code ShedLockConfig}
     * 자바독 — 확정 판정은 {@code RunRepository} 의 상태 조건부 조회 + 회차별 개별 트랜잭션 구조로
     * 이미 "먼저 잡는 인스턴스만 갱신에 성공"이 보장돼, 락이 막을 대상(중복 판정 수행) 자체가
     * 발생할 자리가 없다고 판단했다(Phase 11 T4 판정, 게이트 리뷰 승인).
     */
    private static final List<String> EXEMPT = List.of(
            "RunConfirmationScheduler#confirmDueRuns");

    /**
     * 컨트롤러 소스 루트가 실제로 존재하는지 먼저 확인한다. 경로가 어긋나면 아래 시험이 검사한 게
     * 없어서 초록이 되는데, 그건 규칙이 지켜진 것과 구별되지 않는다.
     */
    @Test
    void 소스_루트가_존재한다() {
        assertThat(SOURCE_ROOT).as("테스트 작업 디렉토리가 backend/ 가 아니면 경로를 고쳐야 한다").isDirectory();
    }

    @Test
    void Scheduled_메서드에는_모두_SchedulerLock_이_붙어있다() {
        List<Method> scheduled = scheduledMethods();

        List<String> unlocked = new ArrayList<>();
        for (Method method : scheduled) {
            String key = method.getDeclaringClass().getSimpleName() + "#" + method.getName();
            if (EXEMPT.contains(key)) {
                continue;
            }
            if (method.getAnnotation(SchedulerLock.class) == null) {
                unlocked.add(key);
            }
        }

        assertThat(unlocked)
                .as("@Scheduled 메서드에 @SchedulerLock 이 없다(클래스명#메서드명) — 인스턴스 2대 이상이 "
                        + "같은 배치를 동시에 수행하는 것을 막아야 한다. 의도적으로 여는 것이면 근거와 함께 "
                        + "EXEMPT 목록에 등록한다")
                .isEmpty();
    }

    /**
     * 조율자 실측 — 검사 대상 하한을 건다. 0개면 위 시험이 "위반 없음"과 "검사 대상 없음"을
     * 구별하지 못한 채 항상 통과한다({@code ControllerAuthorizationConventionTest} 의
     * {@code 검사_대상_핸들러가_하나도_없으면_실패한다} 와 같은 취지). 2026-09-02 시점 실제
     * {@code @Scheduled} 메서드는 6개다.
     */
    @Test
    void 검사_대상_스케줄_메서드가_하나도_없으면_실패한다() {
        assertThat(scheduledMethods().size())
                .as("@Scheduled 메서드가 최소 1개는 있어야 한다 — 0개면 위 시험이 공허하게 통과한다")
                .isGreaterThanOrEqualTo(1);
    }

    /**
     * Phase 11 이월 ③ 해소(2026-09-03, 조율자) — 면제 목록 <b>자체</b>를 검사한다. 위 시험은 면제
     * 항목을 건너뛰므로, 면제가 근거 없이 늘거나 개명·삭제된 대상을 가리킨 채 남아도 아무 시험도
     * 실패하지 않았다(Phase 11 좌석이 변형으로 실증). 개수를 고정해 항목 추가가 이 단언을 깨뜨리게
     * 하고(계정 상태 게이트 {@code hasSize} 와 같은 방식), 각 항목이 실재하는 {@code @Scheduled}
     * 메서드를 가리키는지 확인해 낡은 면제를 잡는다.
     */
    @Test
    void 면제_목록은_개수가_고정되고_실재하는_스케줄_메서드만_가리킨다() {
        assertThat(EXEMPT)
                .as("면제 목록이 1개에서 바뀌었다 — 항목을 더했으면 EXEMPT 자바독에 근거를 함께 적고 이 수를 고친다")
                .hasSize(1);

        List<String> scheduledKeys = scheduledMethods().stream()
                .map(method -> method.getDeclaringClass().getSimpleName() + "#" + method.getName())
                .toList();
        assertThat(scheduledKeys)
                .as("면제 항목이 실재하지 않는 @Scheduled 메서드를 가리킨다 — 대상이 개명·삭제됐는데 면제만 남았다")
                .containsAll(EXEMPT);
    }

    /**
     * F6(2026-09-06) — 모든 {@code @Scheduled} 메서드가 <b>테스트 JVM 에서 배경 실행이 미뤄져 있는지</b>
     * 고정한다. {@code build.gradle} 의 {@code test} 블록은 스케줄러마다 {@code systemProperty} 로
     * 첫 실행을 하루 미루거나(fixedDelay 형) cron 을 비활성 표현식 {@code '-'} 으로 바꾸는데(cron 형),
     * 새 스케줄러를 추가한 좌석이 그 등재를 빠뜨려도 컴파일·테스트가 전부 초록이라 아무도 모른다 —
     * 실제로 {@code RunUnconfirmedGaugeScheduler}·{@code NoShowEscalationScheduler} 가 그렇게 두 Phase
     * 동안 30초 주기로 돌았고, F5 S3 가 동시성 원인을 추적하다 부수로 발견했다.
     *
     * <p>검사 방식 — 애너테이션의 {@code cron}·{@code initialDelayString} 자리표시자에서 속성 이름을
     * 꺼내 {@link System#getProperty(String)} 로 읽는다. Gradle 의 {@code systemProperty} 는 이 JVM 에
     * 그대로 전달되므로, 등재가 빠지면 값이 {@code null} 이라 그 스케줄러가 목록에 남는다.
     * 자리표시자가 아닌 리터럴을 쓴 스케줄러도 미룰 수단이 없으므로 위반으로 센다.
     */
    @Test
    void Scheduled_메서드는_모두_테스트_실행_동안_미뤄져_있다() {
        List<String> notDeferred = new ArrayList<>();
        for (Method method : scheduledMethods()) {
            String key = method.getDeclaringClass().getSimpleName() + "#" + method.getName();
            String violation = deferralViolation(method.getAnnotation(Scheduled.class));
            if (violation != null) {
                notDeferred.add(key + " — " + violation);
            }
        }

        assertThat(notDeferred)
                .as("@Scheduled 메서드가 테스트 실행 동안 미뤄져 있지 않다 — build.gradle 의 test 블록에 "
                        + "initial-delay-ms 를 86400000 으로(fixedDelay 형) 또는 cron 을 '-' 로(cron 형) "
                        + "systemProperty 등재한다. 배경에서 돌면 테스트가 만든 행을 먼저 집어 단언이 실행 "
                        + "순서에 따라 갈린다")
                .isEmpty();
    }

    private static final long ONE_DAY_MS = 86_400_000L;

    /** {@code ${이름}} · {@code ${이름:기본값}} 형태에서 이름만 꺼낸다. */
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{([^:}]+)(?::[^}]*)?}$");

    /**
     * 미뤄져 있으면 {@code null}, 아니면 위반 사유. cron 형은 비활성 표현식 {@code '-'} 이어야 하고,
     * fixedDelay 형은 {@code initialDelayString} 속성이 하루 이상이어야 한다.
     */
    private String deferralViolation(Scheduled scheduled) {
        if (!scheduled.cron().isEmpty()) {
            String property = placeholderName(scheduled.cron());
            if (property == null) {
                return "cron 이 설정 자리표시자가 아니라 테스트에서 끌 수단이 없다";
            }
            String value = System.getProperty(property);
            return "-".equals(value) ? null : property + " 가 '-' 가 아니다(실제 " + value + ")";
        }
        String property = placeholderName(scheduled.initialDelayString());
        if (property == null) {
            return "initialDelayString 이 설정 자리표시자가 아니라 테스트에서 미룰 수단이 없다";
        }
        String value = System.getProperty(property);
        if (value == null) {
            return property + " 가 테스트 JVM 에 등재되지 않았다";
        }
        try {
            if (Long.parseLong(value) >= ONE_DAY_MS) {
                return null;
            }
        } catch (NumberFormatException ignored) {
            // 숫자가 아니면 아래에서 위반으로 보고한다
        }
        return property + " 가 하루(86400000ms) 미만이다(실제 " + value + ")";
    }

    private String placeholderName(String expression) {
        Matcher matcher = PLACEHOLDER.matcher(expression);
        return matcher.matches() ? matcher.group(1) : null;
    }

    /**
     * {@code src/main/java/src/backend} 아래 소스 파일을 훑어 {@code @Scheduled} 가 실제로 붙은
     * 메서드를 리플렉션으로 모은다. Spring 컨텍스트를 띄우지 않아 슬라이스·통합 시험보다 훨씬 빠르다.
     */
    private List<Method> scheduledMethods() {
        List<Method> result = new ArrayList<>();
        for (Path file : javaSources()) {
            Class<?> clazz = loadClass(file);
            if (clazz == null) {
                continue;
            }
            for (Method method : clazz.getDeclaredMethods()) {
                if (method.getAnnotation(Scheduled.class) != null) {
                    result.add(method);
                }
            }
        }
        return result;
    }

    /**
     * 파일 경로를 정규 클래스명으로 바꿔 로드한다. 로드에 실패하는 경로(예: 컴파일 대상이 아닌
     * 보조 파일)는 조용히 건너뛴다 — 이 시험의 목적은 "로드 가능한 클래스 중 {@code @Scheduled}
     * 를 쓰는 것을 찾는 것"이지 컴파일 자체를 검증하는 것이 아니다(컴파일 실패는 빌드가 막는다).
     */
    private Class<?> loadClass(Path file) {
        String relative = SOURCE_ROOT.relativize(file).toString();
        String className = "src.backend." + relative
                .substring(0, relative.length() - ".java".length())
                .replace('/', '.');
        try {
            return Class.forName(className, false, getClass().getClassLoader());
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            return null;
        }
    }

    private List<Path> javaSources() {
        if (!Files.isDirectory(SOURCE_ROOT)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(SOURCE_ROOT)) {
            return paths.filter(p -> p.getFileName().toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
