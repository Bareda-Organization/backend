package testsupport.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import src.backend.global.common.SeedFixtures;

/**
 * {@code SeedFixtures} 의 모든 상수가 시드가 적재된 DB 에서 상수 이름이 약속하는 역할·상태·소속까지
 * 실제로 일치하는 레코드를 가리키는지 검증한다 — {@code IMPLEMENTATION_PLAN.md §3.3} 이 규정한
 * 세 겹 중 겹①(값 실재 대조)이다.
 *
 * <p>"값이 실재한다"만으로는 부족하다 — {@link #ALL_CHECKS()} 의 각 조건은 role·status·academy_id
 * 처럼 상수 이름이 담고 있는 의미를 전부 WHERE 절에 넣는다. {@code PARENT_A1_LOGIN_ID} 가 실수로
 * {@code rejected} 계정을 가리키게 시드가 바뀌면, "존재하는가"만 보는 테스트는 초록불이지만 이
 * 테스트는 role·status 조건이 어긋나 실패한다.
 */
class SeedFixturesContractTest extends MigratedPostgresTestBase {

    private static final String DUMMY_HASH = "$2a$10$Noeszx0nzJUfNo4ubCD03eNfMVfMD9feMo04y/8DHiFLUBF.JZ/Fq";

    @BeforeAll
    static void 스키마와_시드를_함께_적용한다() {
        migrate(Map.of("seedPasswordHash", DUMMY_HASH), SCHEMA_LOCATION, SEED_LOCATION);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ALL_CHECKS")
    void 상수가_가리키는_행이_실재하고_역할과_상태가_상수_이름과_일치한다(FixtureCheck check) throws SQLException {
        assertThat(existsMatching(check)).as(check.description()).isTrue();
    }

    @Test
    void 상수에_없는_로그인_아이디로_조회하면_0건이다() throws SQLException {
        FixtureCheck bogus = accountCheck("__no_such_login_id__", "parent", "active", null);
        assertThat(existsMatching(bogus))
                .as("시드에 없는 로그인 아이디는 조회 경로 자체가 0건을 반환해야 한다")
                .isFalse();
    }

    @Test
    void bigint_PK_상수는_문자열에서_변환돼_해당_행을_찾는다() throws SQLException {
        long converted = asBigint(SeedFixtures.RUN_IDLE_ID);
        assertThat(converted).isEqualTo(1L);

        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT 1 FROM run WHERE id = ? AND status = 'idle'")) {
            statement.setLong(1, converted);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next())
                        .as("문자열 상수 %s 를 bigint 로 변환한 값으로 run 을 조회할 수 있어야 한다", SeedFixtures.RUN_IDLE_ID)
                        .isTrue();
            }
        }
    }

    /** 문자열 상수를 bigint PK 비교에 쓸 {@code long} 으로 바꾼다 — 변환 지점을 한 곳에 모아 흩어지지 않게 한다. */
    private static long asBigint(String pkConstant) {
        return Long.parseLong(pkConstant);
    }

    /**
     * 클래스 주석의 "{@code SeedFixtures} 의 모든 상수" 선언이 실제로 참인지 — 상수를 나열해 빠뜨리는
     * 대신, {@link #CHECKED_CONSTANTS()} 가 태그한 이름 집합과 리플렉션으로 읽은 실제 필드 집합을
     * 대조해 **누락 자체를 검출**한다(BR-113 잔여, Ruling 348). 새 상수가 생겨도 태그를 안 달면 이
     * 시험이 그 이름을 그대로 짚어 실패한다.
     */
    @Test
    void SeedFixtures의_모든_public_상수는_FixtureCheck로_대조된다() {
        Set<String> checked = CHECKED_CONSTANTS().stream()
                .flatMap(c -> c.fieldNames().stream())
                .collect(Collectors.toSet());

        Set<String> missing = new TreeSet<>(declaredStringConstantNames());
        missing.removeAll(checked);
        missing.removeAll(VALUE_CHECK_EXCLUDED);

        assertThat(missing)
                .as("SeedFixtures 의 public 상수는 전부 FixtureCheck 로 존재를 대조해야 한다"
                        + "(제외 목록 VALUE_CHECK_EXCLUDED 는 이 클래스 상단 참고)")
                .isEmpty();
    }

    /** {@code SeedFixtures} 에 선언된 {@code public static final String} 필드 이름 전부. */
    private static Set<String> declaredStringConstantNames() {
        return Arrays.stream(SeedFixtures.class.getDeclaredFields())
                .filter(field -> Modifier.isPublic(field.getModifiers())
                        && Modifier.isStatic(field.getModifiers())
                        && Modifier.isFinal(field.getModifiers())
                        && field.getType() == String.class)
                .map(Field::getName)
                .collect(Collectors.toSet());
    }

    /**
     * 값 실재 대조 대상 밖 상수 — {@code SeedFixtures} 클래스 상단 javadoc 이 이유를 적어 뒀다.
     * {@code LOCAL_DEFAULT_PASSWORD} 는 DB 에 bcrypt 해시만 저장돼 평문 대조가 불가능하다.
     */
    private static final Set<String> VALUE_CHECK_EXCLUDED = Set.of("LOCAL_DEFAULT_PASSWORD");

    private static boolean existsMatching(FixtureCheck check) throws SQLException {
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(check.sql())) {
            Object[] params = check.params();
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    /** {@code SeedFixtures} 상수 전건에 대응하는 조건 목록 — 하나라도 빠지면 "전건 대조"라는 이름이 거짓이 된다. */
    private static Stream<FixtureCheck> ALL_CHECKS() {
        return CHECKED_CONSTANTS().stream().map(CheckedConstant::check);
    }

    /**
     * 대조 조건마다 "이 조건이 어느 {@code SeedFixtures} 상수(들)의 존재를 확인하는가" 를 이름으로
     * 태그해 둔다 — {@link #SeedFixtures의_모든_public_상수는_FixtureCheck로_대조된다()} 가 이 태그
     * 집합과 리플렉션으로 읽은 실제 필드 집합을 대조해 누락을 검출한다.
     */
    private static List<CheckedConstant> CHECKED_CONSTANTS() {
        return List.of(
                checked(academyCheck(SeedFixtures.ACADEMY_A_ID, SeedFixtures.ACADEMY_A_CODE, "active"),
                        "ACADEMY_A_ID", "ACADEMY_A_CODE"),
                checked(academyCheck(SeedFixtures.ACADEMY_B_ID, SeedFixtures.ACADEMY_B_CODE, "active"),
                        "ACADEMY_B_ID", "ACADEMY_B_CODE"),
                checked(academyCheck(SeedFixtures.ACADEMY_C_ID, SeedFixtures.ACADEMY_C_CODE, "inactive"),
                        "ACADEMY_C_ID", "ACADEMY_C_CODE"),

                checked(accountCheck(SeedFixtures.SYSTEM_ADMIN_LOGIN_ID, "system_admin", "active", null),
                        "SYSTEM_ADMIN_LOGIN_ID"),
                checked(accountCheck(SeedFixtures.STAFF_A_LOGIN_ID, "staff", "active", SeedFixtures.ACADEMY_A_ID),
                        "STAFF_A_LOGIN_ID"),
                checked(accountCheck(SeedFixtures.STAFF_B_LOGIN_ID, "staff", "active", SeedFixtures.ACADEMY_B_ID),
                        "STAFF_B_LOGIN_ID"),
                checked(accountCheck(SeedFixtures.STAFF_PENDING_LOGIN_ID, "staff", "pending", SeedFixtures.ACADEMY_A_ID),
                        "STAFF_PENDING_LOGIN_ID"),
                checked(accountCheck(SeedFixtures.STAFF_C_LOGIN_ID, "staff", "active", SeedFixtures.ACADEMY_C_ID),
                        "STAFF_C_LOGIN_ID"),
                checked(accountCheck(SeedFixtures.PARENT_A1_LOGIN_ID, "parent", "active", SeedFixtures.ACADEMY_A_ID),
                        "PARENT_A1_LOGIN_ID"),
                checked(accountCheck(SeedFixtures.PARENT_A2_LOGIN_ID, "parent", "active", SeedFixtures.ACADEMY_A_ID),
                        "PARENT_A2_LOGIN_ID"),
                checked(accountCheck(SeedFixtures.PARENT_A3_LOGIN_ID, "parent", "active", SeedFixtures.ACADEMY_A_ID),
                        "PARENT_A3_LOGIN_ID"),
                checked(accountCheck(SeedFixtures.PARENT_PENDING_LOGIN_ID, "parent", "pending", SeedFixtures.ACADEMY_A_ID),
                        "PARENT_PENDING_LOGIN_ID"),
                checked(accountCheck(SeedFixtures.PARENT_B1_LOGIN_ID, "parent", "active", SeedFixtures.ACADEMY_B_ID),
                        "PARENT_B1_LOGIN_ID"),
                checked(accountCheck(SeedFixtures.STUDENT_A4_LOGIN_ID, "student", "active", SeedFixtures.ACADEMY_A_ID),
                        "STUDENT_A4_LOGIN_ID"),
                checked(accountCheck(SeedFixtures.STUDENT_REJECTED_LOGIN_ID, "student", "rejected", SeedFixtures.ACADEMY_A_ID),
                        "STUDENT_REJECTED_LOGIN_ID"),
                checked(accountCheck(SeedFixtures.STUDENT_B1_LOGIN_ID, "student", "active", SeedFixtures.ACADEMY_B_ID),
                        "STUDENT_B1_LOGIN_ID"),
                checked(accountCheck(SeedFixtures.DRIVER_A1_LOGIN_ID, "driver", "active", SeedFixtures.ACADEMY_A_ID),
                        "DRIVER_A1_LOGIN_ID"),
                checked(accountCheck(SeedFixtures.DRIVER_A2_LOGIN_ID, "driver", "active", SeedFixtures.ACADEMY_A_ID),
                        "DRIVER_A2_LOGIN_ID"),
                checked(accountCheck(SeedFixtures.DRIVER_BLOCKED_LOGIN_ID, "driver", "blocked", SeedFixtures.ACADEMY_A_ID),
                        "DRIVER_BLOCKED_LOGIN_ID"),
                checked(accountCheck(SeedFixtures.DRIVER_B1_LOGIN_ID, "driver", "active", SeedFixtures.ACADEMY_B_ID),
                        "DRIVER_B1_LOGIN_ID"),
                checked(accountCheck(SeedFixtures.ESCORT_A1_LOGIN_ID, "escort", "active", SeedFixtures.ACADEMY_A_ID),
                        "ESCORT_A1_LOGIN_ID"),
                checked(accountCheck(SeedFixtures.ESCORT_A2_LOGIN_ID, "escort", "active", SeedFixtures.ACADEMY_A_ID),
                        "ESCORT_A2_LOGIN_ID"),
                checked(accountCheck(SeedFixtures.ESCORT_B1_LOGIN_ID, "escort", "active", SeedFixtures.ACADEMY_B_ID),
                        "ESCORT_B1_LOGIN_ID"),

                checked(new FixtureCheck(
                        "STUDENT_SIBLING_1_ID 는 학원 A 소속 계정 미연결 학생이다",
                        "SELECT 1 FROM student WHERE id = ?::bigint AND academy_id = ?::bigint AND account_id IS NULL",
                        new Object[] {SeedFixtures.STUDENT_SIBLING_1_ID, SeedFixtures.ACADEMY_A_ID}),
                        "STUDENT_SIBLING_1_ID"),
                checked(new FixtureCheck(
                        "STUDENT_SIBLING_2_ID 는 학원 A 소속 계정 미연결 학생이다",
                        "SELECT 1 FROM student WHERE id = ?::bigint AND academy_id = ?::bigint AND account_id IS NULL",
                        new Object[] {SeedFixtures.STUDENT_SIBLING_2_ID, SeedFixtures.ACADEMY_A_ID}),
                        "STUDENT_SIBLING_2_ID"),
                checked(new FixtureCheck(
                        "STUDENT_UNLINKED_ID 는 학원 A 소속 계정 미연결 학생이다",
                        "SELECT 1 FROM student WHERE id = ?::bigint AND academy_id = ?::bigint AND account_id IS NULL",
                        new Object[] {SeedFixtures.STUDENT_UNLINKED_ID, SeedFixtures.ACADEMY_A_ID}),
                        "STUDENT_UNLINKED_ID"),
                checked(new FixtureCheck(
                        "GUARDIAN_SIBLINGS_ID 는 parentA1 계정과 연결되고 두 형제 학생을 모두 연결한다",
                        """
                        SELECT 1 FROM guardian g
                        JOIN account a ON a.id = g.account_id
                        WHERE g.id = ?::bigint AND a.login_id = ?
                          AND EXISTS (SELECT 1 FROM guardian_student gs WHERE gs.guardian_id = g.id AND gs.student_id = ?::bigint)
                          AND EXISTS (SELECT 1 FROM guardian_student gs WHERE gs.guardian_id = g.id AND gs.student_id = ?::bigint)
                        """,
                        new Object[] {
                            SeedFixtures.GUARDIAN_SIBLINGS_ID, SeedFixtures.PARENT_A1_LOGIN_ID,
                            SeedFixtures.STUDENT_SIBLING_1_ID, SeedFixtures.STUDENT_SIBLING_2_ID
                        }),
                        "GUARDIAN_SIBLINGS_ID"),

                checked(new FixtureCheck(
                        "BUS_NEAR_FULL_ID 는 정원 4·학생석 2로 잔여석이 근접한 학원 A 버스다",
                        "SELECT 1 FROM bus WHERE id = ?::bigint AND academy_id = ?::bigint AND capacity = 4 AND student_capacity = 2",
                        new Object[] {SeedFixtures.BUS_NEAR_FULL_ID, SeedFixtures.ACADEMY_A_ID}),
                        "BUS_NEAR_FULL_ID"),

                checked(runCheck(SeedFixtures.RUN_IDLE_ID, SeedFixtures.ACADEMY_A_ID, "idle"), "RUN_IDLE_ID"),
                checked(runCheck(SeedFixtures.RUN_CONFIRMED_ID, SeedFixtures.ACADEMY_A_ID, "confirmed"), "RUN_CONFIRMED_ID"),
                checked(runCheck(SeedFixtures.RUN_MOVING_ID, SeedFixtures.ACADEMY_A_ID, "moving"), "RUN_MOVING_ID"),
                checked(runCheck(SeedFixtures.RUN_FINISHED_ID, SeedFixtures.ACADEMY_A_ID, "finished"), "RUN_FINISHED_ID"),
                checked(runCheck(SeedFixtures.RUN_CONFIRMED_ACADEMY_B_ID, SeedFixtures.ACADEMY_B_ID, "confirmed"),
                        "RUN_CONFIRMED_ACADEMY_B_ID"),

                checked(changeRequestCheck(SeedFixtures.CHANGE_REQUEST_PENDING_ID, "pending"),
                        "CHANGE_REQUEST_PENDING_ID"),
                checked(changeRequestCheck(SeedFixtures.CHANGE_REQUEST_APPROVED_ID, "approved"),
                        "CHANGE_REQUEST_APPROVED_ID"),
                checked(changeRequestCheck(SeedFixtures.CHANGE_REQUEST_REJECTED_ID, "rejected"),
                        "CHANGE_REQUEST_REJECTED_ID"),
                checked(changeRequestCheck(SeedFixtures.CHANGE_REQUEST_AUTO_REJECTED_ID, "auto_rejected"),
                        "CHANGE_REQUEST_AUTO_REJECTED_ID"),

                // ── Swagger 경로 변수 "id" 사전 채움용 상수(BR-113 잔여, Ruling 348) ──────
                checked(new FixtureCheck(
                        "SCHEDULE_A_ID 는 학원 A 소속 배차다",
                        "SELECT 1 FROM schedule WHERE id = ?::bigint AND academy_id = ?::bigint",
                        new Object[] {SeedFixtures.SCHEDULE_A_ID, SeedFixtures.ACADEMY_A_ID}),
                        "SCHEDULE_A_ID"),
                checked(new FixtureCheck(
                        "NOTIFICATION_PARENT_A1_UNREAD_ID 는 parentA1 계정 수신, 미확인 상태인 알림이다",
                        """
                        SELECT 1 FROM notification_log n
                        JOIN account a ON a.id = n.recipient_account_id
                        WHERE n.id = ?::bigint AND a.login_id = ? AND n.read_at IS NULL
                        """,
                        new Object[] {SeedFixtures.NOTIFICATION_PARENT_A1_UNREAD_ID, SeedFixtures.PARENT_A1_LOGIN_ID}),
                        "NOTIFICATION_PARENT_A1_UNREAD_ID"),
                checked(new FixtureCheck(
                        "MANAGER_DRIVER_A1_ID 는 driverA1 계정의 기사 매니저 프로필이다",
                        """
                        SELECT 1 FROM manager m
                        JOIN account a ON a.id = m.account_id
                        WHERE m.id = ?::bigint AND a.login_id = ? AND m.role = 'driver'
                        """,
                        new Object[] {SeedFixtures.MANAGER_DRIVER_A1_ID, SeedFixtures.DRIVER_A1_LOGIN_ID}),
                        "MANAGER_DRIVER_A1_ID"),
                checked(new FixtureCheck(
                        "ACCOUNT_STAFF_A_ID 는 staffA 계정의 PK 다",
                        "SELECT 1 FROM account WHERE id = ?::bigint AND login_id = ?",
                        new Object[] {SeedFixtures.ACCOUNT_STAFF_A_ID, SeedFixtures.STAFF_A_LOGIN_ID}),
                        "ACCOUNT_STAFF_A_ID"),
                checked(new FixtureCheck(
                        "ACCOUNT_DRIVER_BLOCKED_ID 는 driverBlocked 계정의 PK 다",
                        "SELECT 1 FROM account WHERE id = ?::bigint AND login_id = ?",
                        new Object[] {SeedFixtures.ACCOUNT_DRIVER_BLOCKED_ID, SeedFixtures.DRIVER_BLOCKED_LOGIN_ID}),
                        "ACCOUNT_DRIVER_BLOCKED_ID"),
                checked(new FixtureCheck(
                        "ROUTE_A_FIXED_ID 는 학원 A 소속 활성 고정 노선이다",
                        "SELECT 1 FROM route WHERE id = ?::bigint AND academy_id = ?::bigint AND active = true",
                        new Object[] {SeedFixtures.ROUTE_A_FIXED_ID, SeedFixtures.ACADEMY_A_ID}),
                        "ROUTE_A_FIXED_ID"),
                checked(new FixtureCheck(
                        "SIGNUP_REQUEST_STAFF_PENDING_ID 는 staffPending 계정의 대기 중 관계자 가입 신청이다",
                        """
                        SELECT 1 FROM signup_request s
                        JOIN account a ON a.id = s.account_id
                        WHERE s.id = ?::bigint AND a.login_id = ? AND s.requested_role = 'staff' AND s.status = 'pending'
                        """,
                        new Object[] {SeedFixtures.SIGNUP_REQUEST_STAFF_PENDING_ID, SeedFixtures.STAFF_PENDING_LOGIN_ID}),
                        "SIGNUP_REQUEST_STAFF_PENDING_ID"),
                checked(new FixtureCheck(
                        "SIGNUP_REQUEST_PARENT_PENDING_ID 는 parentPending 계정의 대기 중 학부모 가입 신청이다",
                        """
                        SELECT 1 FROM signup_request s
                        JOIN account a ON a.id = s.account_id
                        WHERE s.id = ?::bigint AND a.login_id = ? AND s.requested_role = 'parent' AND s.status = 'pending'
                        """,
                        new Object[] {SeedFixtures.SIGNUP_REQUEST_PARENT_PENDING_ID, SeedFixtures.PARENT_PENDING_LOGIN_ID}),
                        "SIGNUP_REQUEST_PARENT_PENDING_ID"),
                checked(new FixtureCheck(
                        "EMERGENCY_ALERT_A_ID 는 학원 A 회차의 미확인 비상 알림이다",
                        "SELECT 1 FROM emergency_alert WHERE id = ?::bigint AND academy_id = ?::bigint AND acked_at IS NULL",
                        new Object[] {SeedFixtures.EMERGENCY_ALERT_A_ID, SeedFixtures.ACADEMY_A_ID}),
                        "EMERGENCY_ALERT_A_ID"),
                checked(new FixtureCheck(
                        "EXCEPTION_REPORT_EXAMPLE_ID 는 학원 A 회차의 예외 보고다",
                        "SELECT 1 FROM exception_report WHERE id = ?::bigint AND academy_id = ?::bigint",
                        new Object[] {SeedFixtures.EXCEPTION_REPORT_EXAMPLE_ID, SeedFixtures.ACADEMY_A_ID}),
                        "EXCEPTION_REPORT_EXAMPLE_ID"));
    }

    /** {@code check} 가 대조하는 {@code SeedFixtures} 상수 이름(들)을 태그한다. */
    private static CheckedConstant checked(FixtureCheck check, String... fieldNames) {
        return new CheckedConstant(Set.of(fieldNames), check);
    }

    private static FixtureCheck academyCheck(String id, String code, String status) {
        return new FixtureCheck(
                "학원 %s(%s) 는 code=%s status=%s 이다".formatted(id, code, code, status),
                "SELECT 1 FROM academy WHERE id = ?::bigint AND code = ? AND status = ?",
                new Object[] {id, code, status});
    }

    private static FixtureCheck accountCheck(String loginId, String role, String status, String academyId) {
        String sql = academyId == null
                ? "SELECT 1 FROM account WHERE login_id = ? AND role = ? AND status = ? AND academy_id IS NULL"
                : "SELECT 1 FROM account WHERE login_id = ? AND role = ? AND status = ? AND academy_id = ?::bigint";
        Object[] params = academyId == null
                ? new Object[] {loginId, role, status}
                : new Object[] {loginId, role, status, academyId};
        return new FixtureCheck(
                "계정 %s 는 role=%s status=%s academy_id=%s 이다".formatted(loginId, role, status, academyId),
                sql, params);
    }

    private static FixtureCheck runCheck(String runId, String academyId, String status) {
        return new FixtureCheck(
                "회차 %s 는 academy_id=%s status=%s 이다".formatted(runId, academyId, status),
                "SELECT 1 FROM run WHERE id = ?::bigint AND academy_id = ?::bigint AND status = ?",
                new Object[] {runId, academyId, status});
    }

    private static FixtureCheck changeRequestCheck(String changeRequestId, String status) {
        return new FixtureCheck(
                "변경요청 %s 는 run_id=%s status=%s 이다".formatted(changeRequestId, SeedFixtures.RUN_CONFIRMED_ID, status),
                "SELECT 1 FROM change_request WHERE id = ?::bigint AND run_id = ?::bigint AND status = ?",
                new Object[] {changeRequestId, SeedFixtures.RUN_CONFIRMED_ID, status});
    }

    /** 상수 하나를 실제로 가리키는지 확인할 SQL 과 그 이유 — 이름은 {@code @ParameterizedTest} 표시용이다. */
    private record FixtureCheck(String description, String sql, Object[] params) {

        @Override
        public String toString() {
            return description;
        }
    }

    /** {@code FixtureCheck} 하나와 그 검사가 대조하는 {@code SeedFixtures} 필드 이름(들). */
    private record CheckedConstant(Set<String> fieldNames, FixtureCheck check) {
    }
}
