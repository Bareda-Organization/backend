package testsupport.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 학원 경계 점검(R46-LATERBE B-4, Ruling 675) — {@code academy_id} 가 없는 자식({@code run_rider} · {@code route_stop} ·
 * {@code run_stop})은 복합 FK 를 걸 수 없어 "다른 학원의 학생·승하차지를 가리키는 행" 을 DB 가 막지 못한다. 점검 쿼리
 * ({@code load/sql/check_academy_boundary.sql} — 부하 DB 점검에도 같은 파일을 쓴다)가 시험 시드({@code db/fixture}) 적재 직후 전부
 * 0건인지 본다. 부하 시드 스크립트가 이 경계를 어겨 {@code run_rider} 18,000건이 학원을 넘은 사례가 있었다(R46 검토 B-4).
 *
 * <p>점검 쿼리가 실제로 어긋남을 세는지도 한 번 본다 — 항상 0 을 돌려주는 쿼리는 "0건" 시험을 공허하게 통과한다.
 */
class SeedBoundaryCheckTest extends MigratedPostgresTestBase {

    private static final String DUMMY_HASH = "$2a$10$Noeszx0nzJUfNo4ubCD03eNfMVfMD9feMo04y/8DHiFLUBF.JZ/Fq";

    private static final Path CHECK_SCRIPT = Path.of("load", "sql", "check_academy_boundary.sql");

    @BeforeAll
    static void 스키마와_시드를_적용한다() {
        migrate(Map.of("seedPasswordHash", DUMMY_HASH), SCHEMA_LOCATION, SEED_LOCATION);
    }

    @Test
    void 시드_적재_직후_학원_경계_불일치는_전부_0건이다() throws Exception {
        try (Connection connection = connection()) {
            Map<String, Long> counts = 점검한다(connection);

            assertThat(counts).as("점검 항목 4개").hasSize(4);
            assertThat(counts.values()).as("항목별 불일치 건수 %s", counts).containsOnly(0L);
            assertThat(count(connection, "SELECT count(*) FROM run_rider")).as("점검이 공허하지 않다 — 탑승자 행이 있다").isPositive();
        }
    }

    @Test
    void 점검_쿼리는_다른_학원의_학생을_가리키는_탑승자를_센다() throws Exception {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                // 어느 학원의 회차 탑승자 한 행을 다른 학원의 학생으로 바꾼다 — 롤백되므로 시드는 그대로다
                statement.execute("""
                        UPDATE run_rider SET student_id = (
                            SELECT s.id FROM student s JOIN run r ON r.id = run_rider.run_id
                            WHERE s.academy_id <> r.academy_id LIMIT 1)
                        WHERE id = (SELECT min(id) FROM run_rider)
                        """);
                assertThat(점검한다(connection).get("run_rider.student_id ↔ run")).isEqualTo(1L);
            } finally {
                connection.rollback();
            }
        }
    }

    private static Map<String, Long> 점검한다(Connection connection) throws SQLException, java.io.IOException {
        Map<String, Long> counts = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(Files.readString(CHECK_SCRIPT))) {
            while (rows.next()) {
                counts.put(rows.getString("check_name"), rows.getLong("mismatches"));
            }
        }
        return counts;
    }

    private static long count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
