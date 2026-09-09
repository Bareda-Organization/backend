package testsupport.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code V2__seed_data.sql}(로컬 데모 시드)이 {@code V1__init_schema.sql} 위에서 예외 없이
 * 적재되고, Swagger 로 역할·상태별 로그인 흐름을 밟을 수 있을 만큼 계정이 갖춰졌는지 본다.
 *
 * <p>이 클래스는 Phase1·Task7 의 TDD RED→GREEN 대상이다 — 단언 3개를 먼저 쓰고, 옛 시드(tenant
 * 테이블 참조)에 대해 RED 를 확인한 뒤, 새 시드를 채워 GREEN 으로 만든다. {@code SeedFixtures}
 * 상수 클래스와의 전수 대조는 이 태스크 범위 밖이며 별도 후속 태스크(SeedFixturesContractTest)다.
 */
class SeedDataLoadTest extends MigratedPostgresTestBase {

    private static final String DUMMY_HASH = "$2a$10$Noeszx0nzJUfNo4ubCD03eNfMVfMD9feMo04y/8DHiFLUBF.JZ/Fq";

    @BeforeAll
    static void 스키마와_시드를_함께_적용한다() {
        migrate(Map.of("seedPasswordHash", DUMMY_HASH), SCHEMA_LOCATION, SEED_LOCATION);
    }

    @Test
    void 역할_6종_각각_active_계정이_최소_1개_존재한다() throws SQLException {
        List<String> roles = List.of("parent", "student", "driver", "escort", "staff", "system_admin");
        for (String role : roles) {
            List<String> ids = queryColumn("""
                    SELECT id FROM account WHERE role = '%s' AND status = 'active'
                    """.formatted(role));
            assertThat(ids).as("역할 %s 의 active 계정이 최소 1개 있어야 한다", role).isNotEmpty();
        }
    }

    @Test
    void 계정_상태_4종_각각_최소_1개_존재한다() throws SQLException {
        List<String> statuses = List.of("pending", "active", "rejected", "blocked");
        for (String status : statuses) {
            List<String> ids = queryColumn("""
                    SELECT id FROM account WHERE status = '%s'
                    """.formatted(status));
            assertThat(ids).as("상태 %s 인 계정이 최소 1개 있어야 한다", status).isNotEmpty();
        }
    }

    private static List<String> queryColumn(String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = connection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }
}
