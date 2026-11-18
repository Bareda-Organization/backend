package testsupport.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import testsupport.db.TwoAcademyRows.Tenant;

/**
 * 학원 경계를 DB 가 지키는지 본다(Ruling 675 의 복합 FK 를 자식 아홉 표로 넓힌 것) — 다른 학원의 학생 · 승하차지 · 회차 · 노선 · 매니저 ·
 * 보호자를 가리키는 자식 행은 애플리케이션 검사와 무관하게 외래 키 위반({@code 23503})으로 거절돼야 한다. 관계마다 한 칸만 다른 학원의 부모로
 * 바꾼 행을 넣는다. 같은 학원끼리의 행이 들어가는지도 함께 봐서 "항상 거절" 로 통과하는 공허한 시험을 막는다.
 */
class AcademyBoundaryConstraintTest extends MigratedPostgresTestBase {

    private static final String FOREIGN_KEY_VIOLATION = "23503";

    private static Tenant mine;

    private static Tenant other;

    private Connection connection;

    @BeforeAll
    static void 학원_둘과_부모_행을_만든다() throws SQLException {
        migrate(SCHEMA_LOCATION);
        try (Connection c = connection()) {
            mine = TwoAcademyRows.tenant(c, "mine");
            other = TwoAcademyRows.tenant(c, "other");
        }
    }

    @BeforeEach
    void 트랜잭션을_연다() throws SQLException {
        connection = connection();
        connection.setAutoCommit(false);
    }

    @AfterEach
    void 되돌린다() throws SQLException {
        connection.rollback();
        connection.close();
    }

    static Stream<Arguments> 관계() {
        return Stream.of("route_stop.route_id", "route_stop.stop_id", "run_stop.stop_id", "run_rider.run_id",
                "run_rider.student_id", "run_rider.stop_id", "assignment.run_id", "assignment.manager_id",
                "guardian_student.guardian_id", "guardian_student.student_id", "weekly_address.student_id",
                "weekly_address.stop_id", "run_forced_addition.run_id", "run_forced_addition.student_id",
                "run_forced_addition.stop_id", "boarding_intent.run_id", "boarding_intent.student_id",
                "run_transfer.student_id", "run_transfer.from_run_id", "run_transfer.to_run_id",
                "run_transfer.stop_id").map(Arguments::of);
    }

    @ParameterizedTest(name = "{0} 가 다른 학원의 부모를 가리키면 DB 가 거절한다")
    @MethodSource("관계")
    void 다른_학원의_부모를_가리키는_자식_행은_거절된다(String relation) {
        String[] parts = relation.split("\\.");
        Map<String, Object> row = TwoAcademyRows.validChild(parts[0], mine);
        row.put(parts[1], other.parent(parts[1]));

        assertThatThrownBy(() -> TwoAcademyRows.insert(connection, parts[0], row))
                .as("%s 가 다른 학원의 행을 가리키는데 DB 가 받아들였다", relation)
                .isInstanceOfSatisfying(SQLException.class,
                        e -> assertThat(e.getSQLState()).as(e.getMessage()).isEqualTo(FOREIGN_KEY_VIOLATION));
    }

    @ParameterizedTest(name = "{0} 의 같은 학원끼리의 행은 들어간다")
    @ValueSource(strings = {"route_stop", "run_stop", "run_rider", "assignment", "guardian_student",
            "weekly_address", "run_forced_addition", "boarding_intent", "run_transfer"})
    void 같은_학원끼리의_자식_행은_들어간다(String table) throws SQLException {
        assertThat(TwoAcademyRows.insert(connection, table, TwoAcademyRows.validChild(table, mine))).isPositive();
    }

    @ParameterizedTest(name = "{0} 는 학원 칸이 NOT NULL 이다")
    @ValueSource(strings = {"route_stop", "run_stop", "run_rider", "assignment", "guardian_student",
            "weekly_address", "run_forced_addition", "boarding_intent", "run_transfer"})
    void 자식_아홉_표는_학원_칸이_필수다(String table) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = ? AND column_name = 'academy_id'")) {
            statement.setString(1, table);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).as("%s 에 academy_id 칸이 없다", table).isTrue();
                assertThat(rows.getString(1)).isEqualTo("NO");
            }
        }
    }
}
