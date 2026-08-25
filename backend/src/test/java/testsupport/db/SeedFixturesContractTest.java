package testsupport.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
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
        return Stream.of(
                academyCheck(SeedFixtures.ACADEMY_A_ID, SeedFixtures.ACADEMY_A_CODE, "active"),
                academyCheck(SeedFixtures.ACADEMY_B_ID, SeedFixtures.ACADEMY_B_CODE, "active"),
                academyCheck(SeedFixtures.ACADEMY_C_ID, SeedFixtures.ACADEMY_C_CODE, "inactive"),

                accountCheck(SeedFixtures.SYSTEM_ADMIN_LOGIN_ID, "system_admin", "active", null),
                accountCheck(SeedFixtures.STAFF_A_LOGIN_ID, "staff", "active", SeedFixtures.ACADEMY_A_ID),
                accountCheck(SeedFixtures.STAFF_B_LOGIN_ID, "staff", "active", SeedFixtures.ACADEMY_B_ID),
                accountCheck(SeedFixtures.STAFF_PENDING_LOGIN_ID, "staff", "pending", SeedFixtures.ACADEMY_A_ID),
                accountCheck(SeedFixtures.STAFF_C_LOGIN_ID, "staff", "active", SeedFixtures.ACADEMY_C_ID),
                accountCheck(SeedFixtures.PARENT_A1_LOGIN_ID, "parent", "active", SeedFixtures.ACADEMY_A_ID),
                accountCheck(SeedFixtures.PARENT_A2_LOGIN_ID, "parent", "active", SeedFixtures.ACADEMY_A_ID),
                accountCheck(SeedFixtures.PARENT_A3_LOGIN_ID, "parent", "active", SeedFixtures.ACADEMY_A_ID),
                accountCheck(SeedFixtures.PARENT_PENDING_LOGIN_ID, "parent", "pending", SeedFixtures.ACADEMY_A_ID),
                accountCheck(SeedFixtures.PARENT_B1_LOGIN_ID, "parent", "active", SeedFixtures.ACADEMY_B_ID),
                accountCheck(SeedFixtures.STUDENT_A4_LOGIN_ID, "student", "active", SeedFixtures.ACADEMY_A_ID),
                accountCheck(SeedFixtures.STUDENT_REJECTED_LOGIN_ID, "student", "rejected", SeedFixtures.ACADEMY_A_ID),
                accountCheck(SeedFixtures.STUDENT_B1_LOGIN_ID, "student", "active", SeedFixtures.ACADEMY_B_ID),
                accountCheck(SeedFixtures.DRIVER_A1_LOGIN_ID, "driver", "active", SeedFixtures.ACADEMY_A_ID),
                accountCheck(SeedFixtures.DRIVER_A2_LOGIN_ID, "driver", "active", SeedFixtures.ACADEMY_A_ID),
                accountCheck(SeedFixtures.DRIVER_BLOCKED_LOGIN_ID, "driver", "blocked", SeedFixtures.ACADEMY_A_ID),
                accountCheck(SeedFixtures.DRIVER_B1_LOGIN_ID, "driver", "active", SeedFixtures.ACADEMY_B_ID),
                accountCheck(SeedFixtures.ESCORT_A1_LOGIN_ID, "escort", "active", SeedFixtures.ACADEMY_A_ID),
                accountCheck(SeedFixtures.ESCORT_A2_LOGIN_ID, "escort", "active", SeedFixtures.ACADEMY_A_ID),
                accountCheck(SeedFixtures.ESCORT_B1_LOGIN_ID, "escort", "active", SeedFixtures.ACADEMY_B_ID),

                new FixtureCheck(
                        "STUDENT_SIBLING_1_ID 는 학원 A 소속 계정 미연결 학생이다",
                        "SELECT 1 FROM student WHERE id = ?::bigint AND academy_id = ?::bigint AND account_id IS NULL",
                        new Object[] {SeedFixtures.STUDENT_SIBLING_1_ID, SeedFixtures.ACADEMY_A_ID}),
                new FixtureCheck(
                        "STUDENT_SIBLING_2_ID 는 학원 A 소속 계정 미연결 학생이다",
                        "SELECT 1 FROM student WHERE id = ?::bigint AND academy_id = ?::bigint AND account_id IS NULL",
                        new Object[] {SeedFixtures.STUDENT_SIBLING_2_ID, SeedFixtures.ACADEMY_A_ID}),
                new FixtureCheck(
                        "STUDENT_UNLINKED_ID 는 학원 A 소속 계정 미연결 학생이다",
                        "SELECT 1 FROM student WHERE id = ?::bigint AND academy_id = ?::bigint AND account_id IS NULL",
                        new Object[] {SeedFixtures.STUDENT_UNLINKED_ID, SeedFixtures.ACADEMY_A_ID}),
                new FixtureCheck(
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

                new FixtureCheck(
                        "BUS_NEAR_FULL_ID 는 정원 4·학생석 2로 잔여석이 근접한 학원 A 버스다",
                        "SELECT 1 FROM bus WHERE id = ?::bigint AND academy_id = ?::bigint AND capacity = 4 AND student_capacity = 2",
                        new Object[] {SeedFixtures.BUS_NEAR_FULL_ID, SeedFixtures.ACADEMY_A_ID}),

                runCheck(SeedFixtures.RUN_IDLE_ID, SeedFixtures.ACADEMY_A_ID, "idle"),
                runCheck(SeedFixtures.RUN_CONFIRMED_ID, SeedFixtures.ACADEMY_A_ID, "confirmed"),
                runCheck(SeedFixtures.RUN_MOVING_ID, SeedFixtures.ACADEMY_A_ID, "moving"),
                runCheck(SeedFixtures.RUN_FINISHED_ID, SeedFixtures.ACADEMY_A_ID, "finished"),
                runCheck(SeedFixtures.RUN_CONFIRMED_ACADEMY_B_ID, SeedFixtures.ACADEMY_B_ID, "confirmed"),

                changeRequestCheck(SeedFixtures.CHANGE_REQUEST_PENDING_ID, "pending"),
                changeRequestCheck(SeedFixtures.CHANGE_REQUEST_APPROVED_ID, "approved"),
                changeRequestCheck(SeedFixtures.CHANGE_REQUEST_REJECTED_ID, "rejected"),
                changeRequestCheck(SeedFixtures.CHANGE_REQUEST_AUTO_REJECTED_ID, "auto_rejected"));
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
}
