package src.backend.global.security.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * 학원 격리를 <b>빠뜨린 저장소 조회를 실패로 만드는</b> 회귀 방지 테스트 — 이 태스크의 핵심 산출물이다.
 *
 * <p>격리는 DB 레벨(RLS)이 아니라 애플리케이션 코드가 강제하므로(CLAUDE.md), 조건 하나를 빠뜨리면
 * 그대로 샌다. 특히 목록 조회는 조건이 빠져도 동작해서 기능 테스트를 통과한다(ARCHITECTURE §6.1).
 * 그래서 "격리를 넣었는가" 가 아니라 <b>"넣지 않은 곳이 있는가"</b> 를 기계가 세게 한다.
 *
 * <p><b>검사 대상은 ERD §6.1 에 등재된 38개 테이블 전부</b>다 — 직접 보유 17 + 부모 경유 21.
 * 부모 경유 테이블은 {@code academy_id} 컬럼이 부재해 조건을 붙일 자리가 부모 조인뿐이고, 그 사실이
 * 곧 빠뜨리기 쉬운 이유다. 그래서 두 분류의 저장소 조회를 같은 규칙으로 묶는다 — 아래 셋 중 하나.
 * <ol>
 *   <li><b>학원으로 좁혀짐</b> — 판정 축이 조회 형태에 따라 갈린다({@link AcademyScopeRule#isNarrowedByAcademy}).
 *       {@code @Query} 메서드는 <b>WHERE 절 조건문</b> 안에 학원 식별자가 있어야 하고, 파생 조회
 *       메서드는 <b>이름</b>에 {@code AcademyId} 가 있어야 한다. {@code @Param} 은 어느 쪽에서도 근거로
 *       쓰지 않는다 — 쿼리 본문·메서드 이름과 무관하게 시그니처에만 존재해, {@code WHERE} 절의 조건을
 *       지워도 그대로 남기 때문이다(Phase 7 T6 실측 결함의 본체)</li>
 *   <li><b>부모 경유로 좁혀짐</b> — {@code @Query} 가 부모의 {@code academyId} 를 WHERE 절의 조인 조건에
 *       씀. 1과 같은 술어로 판정된다 — 자식 테이블에 컬럼이 없어도 WHERE 절에 조인 조건이 있으면 잡힌다</li>
 *   <li><b>{@link AcademyScopeExempt}</b> 로 좁히지 않는 근거를 밝힘</li>
 * </ol>
 * <b>넷째 선택지는 두지 않는다.</b> 표시 없는 새 조회는 이 테스트가 실패시키므로, 다음 Phase 가
 * {@code run_stop} · {@code run_rider} · {@code assignment} · {@code route_stop} 저장소를 만들면서
 * 학원 조건을 빠뜨리면 그 자리에서 빌드가 깨진다.
 *
 * <p><b>이 장치가 보지 않는 것</b> — 설계상 런타임 대조에 맡긴 경계이며, 여기 없는 것은 기계가 아니라
 * 사람이 지켜야 한다.
 * <ul>
 *   <li><b>단건 조회의 뒤처리</b>({@code findById} · {@code getReferenceById} · {@code existsById}) —
 *       조회 자체는 격리와 무관하고 관건은 그 뒤에 {@link AcademyScope#assertAccessible} 이 오는가인데,
 *       그것은 호출 <b>순서</b>라 소스 텍스트로 판정할 수 없다. {@code AcademyScopeIsolationTest} 가
 *       HTTP 왕복으로 {@code 403 ACADEMY_SCOPE_VIOLATION} 을 대조한다</li>
 *   <li><b>호출부가 넘기는 값의 출처</b> — 근거가 "계정 경유" 인 예외는 호출부가 <b>토큰의</b>
 *       {@code accountId} 를 넘긴다는 전제 위에 서 있고, 이 테스트는 호출부를 보지 않는다.
 *       각 {@code reason} 이 그 전제를 문장으로 적는다</li>
 *   <li><b>테넌트 루트 {@code academy} 와 {@code system_admin}</b> — ERD §6.1 의 두 분류 어디에도
 *       속하지 않는다({@link #ERD_OUT_OF_SCOPE_TABLES})</li>
 * </ul>
 *
 * <p>클래스가 {@code reference.md §20.2} 의 200줄을 넘는다 — 바뀌는 계기가 다른 두 축을 이미 갈랐다.
 * <b>수집</b>(패키지 워킹·리플렉션·소스 텍스트)은 {@link AcademyScopeScan}, <b>판정 술어</b>
 * ("좁혀졌다"·"전건 조회다" 의 정의)는 {@link AcademyScopeRule}. 여기 남은 것은 <b>ERD §6.1 표 3개와
 * 그 표에 거는 단언들</b>이라 더 나누면 표와 그 표를 쓰는 단언이 다른 파일에 놓여 정의처가 흩어진다.
 * 잔여 초과분은 대부분 <b>단언 하나하나가 무엇을 막는지 적은 주석</b>이다 — 이 검사는 실패 메시지를
 * 읽는 사람이 규칙의 근거까지 함께 봐야 꺼 버리지 않는다.
 */
class AcademyScopeRepositoryConventionTest {

    /**
     * ERD §6.1 의 <b>직접 보유</b> 표를 그대로 옮긴 테이블 목록(17개) — 코드에서 유도하지 않고 손으로 적는다.
     * 코드에서 유도하면 엔티티에 {@code academyId} 를 빠뜨리는 실수와 함께 틀려 아무것도 못 잡는다
     * ({@code PublicEndpoints} ↔ {@code EXPECTED_PUBLIC_ENDPOINTS} 와 같은 이유의 독립 축이다).
     */
    private static final Set<String> ERD_DIRECT_ACADEMY_TABLES = Set.of(
            "academy_setting", "account", "signup_request", "academy_staff",
            "student", "guardian", "manager", "bus", "stop",
            "schedule", "route", "run",
            "change_request", "notification_log",
            "audit_log", "exception_report", "emergency_alert");

    /**
     * ERD §6.1 의 <b>부모 경유</b> 표를 그대로 옮긴 테이블 목록(21개, ERD §6.2 소계와 일치) — 학원
     * 범위 자원이지만
     * {@code academy_id} 컬럼이 부재해 부모를 조인해야만 학원이 결정된다.
     *
     * <p>이 목록이 위 17개와 <b>같은 규칙을 받는다</b> — 컬럼이 없다는 것은 격리가 면제된다는 뜻이
     * 아니라 조건을 붙일 자리가 부모 조인뿐이라는 뜻이다. 자식 단독 조회 경로를 만들면 조건을 붙일
     * 자리가 사라진다(ERD §6.2).
     */
    private static final Set<String> ERD_PARENT_ACADEMY_TABLES = Set.of(
            "verification_code", "device_token",
            "weekly_address", "guardian_student", "link_request", "link_code",
            "route_stop",
            "confirmed_route", "route_version", "run_stop", "run_rider",
            "assignment", "waypoint", "boarding_intent", "run_position", "run_forced_addition",
            "no_show_case", "no_show_contact", "rider_status_history",
            "notification_setting", "refresh_token");

    /**
     * ERD §6.1 <b>서두</b>가 두 분류 어디에도 두지 않는다고 지목한 테이블 2개 — 학원 격리 규칙의 대상
     * 밖이다. 위 두 상수와 마찬가지로 <b>문서를 그대로 옮긴 것</b>이며 코드에서 유도하지 않는다.
     *
     * <p>{@code academy} 는 테넌트 루트 자신이라 자기 자신으로 좁힌다는 말이 성립하지 않고,
     * {@code system_admin} 은 학원 소속이 부재한 전 학원 범위 계정이라 좁힐 학원 자체가 없다.
     * {@code system_admin} 을 부모 경유로 세면 부모 경유가 22개가 되어 <b>ERD §6.2 의 소계 21 과
     * 어긋난다</b> — 문서도 그 근거를 서두에 함께 적고 있다.
     *
     * <p>이 집합을 명시해 두는 이유는 <b>ERD 표에 없는 새 테이블의 저장소가 조용히 검사 대상 밖으로
     * 빠지는 것</b>을 막기 위함이다({@link #모든_저장소의_엔티티가_ERD_6_1_의_분류_안에_있다}).
     * 세 상수의 값 전부가 "문서의 충실한 사본" 이라는 전제에서 나오므로, 문서와 어긋나는 것을 발견하면
     * <b>코드에서 말없이 교정하지 말고 문서를 먼저 고친다</b> — 사본이 원본과 달라지는 순간 이 장치는
     * 아무것도 보증하지 않는다.
     */
    private static final Set<String> ERD_OUT_OF_SCOPE_TABLES = Set.of("academy", "system_admin");

    /**
     * 학원으로 좁히면 <b>깨지는</b> 조회 6개 — 로그인·토큰 재발급·계정 복구는 소속을 아직 모르는 상태에서
     * 시작하고, 소속을 알아야 좁힐 수 있는데 좁혀야 소속을 안다는 순환이 된다(Ruling 100).
     *
     * <p>{@code existsByLoginId} 만은 근거가 다르다 — 격리 예외가 아니라 <b>유일성 제약 그 자체</b>다.
     * 아이디는 전 학원 통틀어 유일해야 하는데 학원별로 좁혀 세면 타 학원과 같은 아이디를 허용하게 되고,
     * 그러면 로그인 시 어느 계정인지 결정할 수단이 사라진다.
     */
    private static final List<String> MUST_STAY_UNSCOPED = List.of(
            "AccountRepository#findByPhone",
            "AccountRepository#findByLoginId",
            "AccountRepository#existsByLoginId",
            "RefreshTokenRepository#findByTokenHash",
            "RefreshTokenRepository#findAllByAccountIdAndRevokedAtIsNull",
            "VerificationCodeRepository#findTopByPhoneAndPurposeOrderByCreatedAtDesc");

    @Test
    void 소스_루트가_존재한다() {
        assertThat(AcademyScopeScan.SOURCE_ROOT)
                .as("작업 디렉토리가 backend/ 가 아니면 아래 스캔이 전부 공집합으로 초록이 된다")
                .isDirectory();
    }

    /**
     * 저장소 수집의 <b>정수 하한</b> — 아래 세 검사가 전부 이 목록을 순회하므로, 목록이 비면 규칙이
     * 지켜진 것과 구별되지 않는다.
     *
     * <p>{@link AcademyScopeScan#repositoryInterfaces()} 가 {@code repository} 라는 <b>패키지 이름</b>에
     * 결합돼 있어, 저장소를 {@code persistence} 같은 다른 이름에 두면 수집이 통째로 공집합이 된다.
     * 엔티티 쪽은 17·20 이라는 정수 하한이 있으나 저장소 쪽에는 부재해, 개별 검사의
     * {@code isNotEmpty()} 만으로는 <b>9개 중 8개가 사라지는</b> 형태를 못 잡는다(리뷰 라운드 2 m-2).
     *
     * <p>하한을 등호 아닌 <b>이상</b>으로 둔 것은 Phase 5·9 가 저장소를 더할 때 이 단언이 먼저 깨지는
     * 것을 피하기 위함이다 — 여기서 막을 것은 증가가 아니라 <b>수집 실패로 인한 감소</b>다.
     */
    @Test
    void 학원_범위_저장소가_최소_9개_수집된다() {
        assertThat(scopeGovernedRepositories().stream().map(Class::getSimpleName).sorted().toList())
                .as("수집된 저장소가 하한 미만 — repository 패키지 이름이 바뀌었거나 저장소가 다른 곳으로 옮겨졌다")
                .hasSizeGreaterThanOrEqualTo(9);
    }

    /**
     * 격리 대상의 정의처를 고정한다 — 엔티티에 {@code academyId} 를 새로 넣거나 빼면 이 단언이 먼저 깨진다.
     * 이 축이 없으면 아래 저장소 검사가 "대상을 못 찾아서" 조용히 통과할 수 있다.
     */
    @Test
    void academy_id_직접_보유_엔티티가_ERD_6_1_의_17개와_일치한다() {
        Set<String> actual = new LinkedHashSet<>();
        for (Class<?> entity : AcademyScopeScan.entityClasses()) {
            if (AcademyScopeScan.hasAcademyIdField(entity)) {
                actual.add(AcademyScopeScan.tableName(entity));
            }
        }

        assertThat(actual)
                .as("ERD §6.1 직접 보유 표와 어긋난다 — 문서와 코드 중 어느 쪽이 틀렸는지 판정하고 나서 이 목록을 고친다")
                .containsExactlyInAnyOrderElementsOf(ERD_DIRECT_ACADEMY_TABLES);
        assertThat(actual).hasSize(17);
    }

    /**
     * 부모 경유 분류의 정의처를 고정한다 — 21개 테이블이 전부 엔티티로 실재하고, 그 엔티티가
     * {@code academyId} 를 <b>갖지 않는</b> 것까지 본다.
     *
     * <p>컬럼이 생기면 그 테이블은 직접 보유로 분류가 바뀐 것이라 ERD §6.1 과 §5.3 선행 인덱스가 함께
     * 달라져야 한다. 코드만 바뀌고 문서가 남으면 다음 사람이 조인 조건을 계속 붙인다.
     */
    @Test
    void 부모_경유_21개_테이블이_전부_엔티티로_존재하고_academy_id_를_보유하지_않는다() {
        Map<String, Class<?>> byTable = AcademyScopeScan.entitiesByTable();

        assertThat(byTable.keySet())
                .as("ERD §6.1 부모 경유 표에 있는데 엔티티가 부재한 테이블 — 표와 코드 중 어느 쪽이 틀렸는지 판정한다")
                .containsAll(ERD_PARENT_ACADEMY_TABLES);
        assertThat(ERD_PARENT_ACADEMY_TABLES).hasSize(21);

        List<String> nowDirect = ERD_PARENT_ACADEMY_TABLES.stream()
                .filter(table -> AcademyScopeScan.hasAcademyIdField(byTable.get(table)))
                .sorted()
                .toList();

        assertThat(nowDirect)
                .as("부모 경유로 분류된 테이블에 academy_id 가 생겼다 — ERD §6.1 분류와 §5.3 선행 인덱스를 함께 고친다")
                .isEmpty();
    }

    /**
     * ERD §6.1 표에 없는 테이블의 저장소가 조용히 검사 대상 밖으로 빠지는 것을 막는다 — 새 테이블을
     * 만들면 <b>분류부터</b> 하게 강제한다. 이것이 없으면 Phase 5·9 가 새 테이블을 더할 때 위 두 표에
     * 등재하지 않는 것만으로 규칙 전체를 우회할 수 있다.
     */
    @Test
    void 모든_저장소의_엔티티가_ERD_6_1_의_분류_안에_있다() {
        List<String> unclassified = AcademyScopeScan.repositoryInterfaces().stream()
                .map(AcademyScopeScan::entityTypeOf)
                .filter(entity -> entity != null)
                .map(AcademyScopeScan::tableName)
                .distinct()
                .filter(table -> !ERD_DIRECT_ACADEMY_TABLES.contains(table))
                .filter(table -> !ERD_PARENT_ACADEMY_TABLES.contains(table))
                .filter(table -> !ERD_OUT_OF_SCOPE_TABLES.contains(table))
                .sorted()
                .toList();

        assertThat(unclassified)
                .as("ERD §6.1 의 직접 보유·부모 경유·대상 밖 어디에도 없는 테이블 — 분류를 정하고 표에 등재한다")
                .isEmpty();
    }

    /**
     * 학원 범위 저장소(직접 보유 + 부모 경유)의 조회는 학원 조건을 갖거나 예외임을 밝혀야 한다 —
     * 둘 다 아니면 위반이다. 새 조회를 아무 표시 없이 추가하는 것이 이 시스템에서 격리가 새는 표준 경로다.
     */
    @Test
    void 학원_범위_저장소의_모든_조회가_학원_조건을_갖거나_예외로_표시된다() {
        List<Method> methods = scopeGovernedRepositoryMethods();

        assertThat(methods)
                .as("검사 대상 조회가 0개면 이 테스트는 규칙이 지켜진 것과 구별되지 않는다")
                .isNotEmpty();

        List<String> unmarked = methods.stream()
                .filter(method -> !AcademyScopeRule.isNarrowedByAcademy(method))
                .filter(method -> method.getAnnotation(AcademyScopeExempt.class) == null)
                .map(AcademyScopeScan::key)
                .sorted()
                .toList();

        assertThat(unmarked)
                .as("학원 조건도 @AcademyScopeExempt 도 없는 조회 — 격리를 빠뜨렸거나 예외 근거를 안 적었다")
                .isEmpty();
    }

    /** 예외 표시는 근거와 함께여야 한다 — 근거 없는 예외는 격리 누락과 구별되지 않는다. */
    @Test
    void 예외로_표시된_조회는_전부_근거_문구를_갖는다() {
        List<AcademyScopeExempt> exemptions = scopeGovernedRepositoryMethods().stream()
                .map(method -> method.getAnnotation(AcademyScopeExempt.class))
                .filter(annotation -> annotation != null)
                .toList();

        assertThat(exemptions).isNotEmpty();
        assertThat(exemptions).allSatisfy(annotation ->
                assertThat(annotation.reason()).as("reason 이 비어 있다").isNotBlank());
    }

    /**
     * 반대 방향의 사고를 막는다 — 로그인·재발급·복구 조회에 "격리를 빠뜨렸다" 며 학원 조건을 넣으면
     * 로그인이 통째로 실패한다. 애너테이션 유지와 <b>조건 부재</b>를 함께 고정해, 표시만 남기고
     * 조건을 넣는 절반짜리 변경도 실패로 만든다.
     */
    @Test
    void 로그인과_복구_경로_조회_6개는_학원으로_좁혀지지_않은_채_남는다() {
        List<Method> named = allRepositoryMethods().stream()
                .filter(method -> MUST_STAY_UNSCOPED.contains(AcademyScopeScan.key(method)))
                .toList();

        assertThat(named.stream().map(AcademyScopeScan::key))
                .as("Ruling 100 이 지목한 조회가 사라졌거나 이름이 바뀌었다")
                .containsExactlyInAnyOrderElementsOf(MUST_STAY_UNSCOPED);

        assertThat(named).allSatisfy(method -> {
            assertThat(method.getAnnotation(AcademyScopeExempt.class))
                    .as("%s 의 예외 표시가 사라졌다 — 다음 사람이 격리 누락으로 오인한다", AcademyScopeScan.key(method))
                    .isNotNull();
            assertThat(AcademyScopeRule.isNarrowedByAcademy(method))
                    .as("%s 에 학원 조건이 붙었다 — 소속 미상 상태에서 시작하는 흐름이라 조회가 항상 0건이 된다",
                            AcademyScopeScan.key(method))
                    .isFalse();
        });
    }

    /**
     * 상속 메서드로 새는 경로를 막는다 — {@code JpaRepository.findAll()} 은 선언하지 않아도 존재하고,
     * 위 검사가 <b>선언된 메서드만</b> 보므로 여기서 따로 잡지 않으면 그대로 전 학원 조회가 된다.
     *
     * <p>호출({@code repo.findAll()})과 메서드 참조({@code repo::findAll})를 함께 본다 — 한쪽만 보면
     * 다른 형태로 쓰는 것만으로 검사가 우회된다.
     */
    @Test
    void 프로덕션_코드가_학원_범위_저장소의_전건_조회를_호출하지_않는다() {
        List<String> scopedRepositoryNames = scopeGovernedRepositories().stream()
                .map(Class::getSimpleName)
                .toList();
        List<String> violations = new ArrayList<>();
        int inspectedFields = 0;

        for (Path source : AcademyScopeScan.mainSources()) {
            String body = AcademyScopeScan.read(source);
            for (String repositoryName : scopedRepositoryNames) {
                for (String field : AcademyScopeScan.fieldNamesOfType(body, repositoryName)) {
                    inspectedFields++;
                    violations.addAll(AcademyScopeRule.bulkReadsIn(body, field, source));
                }
            }
        }

        assertThat(inspectedFields)
                .as("학원 범위 저장소를 주입받은 프로덕션 필드가 0개면 이 검사는 아무것도 보지 않은 것이다")
                .isPositive();
        assertThat(violations)
                .as("학원 조건 없는 전건 조회 — 학원 조건이 붙은 조회 메서드를 저장소에 선언해 쓴다")
                .isEmpty();
    }

    // ── 검사 대상 수집 ─────────────────────────────────────────────────────


    /** ERD §6.1 에 등재된 테이블(직접 보유 17 + 부모 경유 21)의 저장소 — 두 분류가 같은 규칙을 받는다. */
    private static List<Class<?>> scopeGovernedRepositories() {
        return AcademyScopeScan.repositoryInterfaces().stream()
                .filter(repository -> {
                    Class<?> entity = AcademyScopeScan.entityTypeOf(repository);
                    if (entity == null) {
                        return false;
                    }
                    String table = AcademyScopeScan.tableName(entity);
                    return ERD_DIRECT_ACADEMY_TABLES.contains(table) || ERD_PARENT_ACADEMY_TABLES.contains(table);
                })
                .toList();
    }

    private static List<Method> scopeGovernedRepositoryMethods() {
        return scopeGovernedRepositories().stream()
                .flatMap(repository -> Arrays.stream(repository.getDeclaredMethods()))
                .toList();
    }

    private static List<Method> allRepositoryMethods() {
        return AcademyScopeScan.repositoryInterfaces().stream()
                .flatMap(repository -> Arrays.stream(repository.getDeclaredMethods()))
                .toList();
    }
}
