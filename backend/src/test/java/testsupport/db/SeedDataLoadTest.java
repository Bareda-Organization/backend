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

    /**
     * R20 A 목표 1 — {@code confirmOne} 이 {@code est_duration_min}·{@code est_distance_km} 을
     * 채우는 것은 이미 실코드로 확인했다({@code RunConfirmationPersistence.persist} →
     * {@code RouteVersion.forConfirmedRoute}). 시드가 그 두 컬럼을 INSERT 문 자체에서 빠뜨려
     * 6건 전부 NULL 이었다 — 채워야 할 것은 시드뿐이다.
     */
    @Test
    void route_version은_est_duration_min과_est_distance_km이_전부_채워진다() throws SQLException {
        List<String> nullRows = queryColumn("""
                SELECT id FROM route_version WHERE est_duration_min IS NULL OR est_distance_km IS NULL
                """);
        assertThat(nullRows).as("est_duration_min·est_distance_km 가 NULL 인 route_version 행 id 목록").isEmpty();
    }

    /**
     * R20 A 목표 2 — 운행이 끝난 회차(run id=4)도 확정 노선이 있어야 관계자 웹 지도에 경로가
     * 남는다. 지금은 confirmed_route 행 자체가 없다.
     */
    @Test
    void 운행_종료_회차도_확정_노선을_가진다() throws SQLException {
        List<String> versionIds = queryColumn("""
                SELECT current_version_id FROM confirmed_route WHERE run_id = 4
                """);
        assertThat(versionIds).as("run id=4 의 confirmed_route.current_version_id").hasSize(1);
        assertThat(versionIds.get(0)).as("run id=4 의 확정 버전은 NULL 이 아니어야 한다").isNotEqualTo("null");
    }

    /**
     * R20 A 목표 3 — 운행 중(moving) 회차(run id=3)는 폴백이 아니라 실제 도로 경로여야 한다.
     * {@code road_path} 길이가 정차지 수(5)보다 많아야 도로를 따라 굽은 실측 경로다(정차지 수와
     * 같으면 직선을 그대로 이은 것과 구별되지 않는다 — {@code RunConfirmationServiceLiveTest} 목표
     * 3 과 같은 판단 기준).
     */
    @Test
    void 운행_중_회차의_확정_노선은_폴백이_아니고_road_path가_정차지_수보다_많다() throws SQLException {
        try (Connection connection = connection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("""
                        SELECT rv.fallback_used, jsonb_array_length(rv.road_path)
                        FROM route_version rv
                        JOIN confirmed_route cr ON cr.current_version_id = rv.id
                        WHERE cr.run_id = 3
                        """)) {
            assertThat(rows.next()).as("run id=3 의 현재 확정 버전이 존재해야 한다").isTrue();
            assertThat(rows.getBoolean(1)).as("run id=3 은 폴백이 아니어야 한다").isFalse();
            assertThat(rows.getInt(2)).as("run id=3 의 road_path 길이는 정차지 수(5)보다 많아야 한다")
                    .isGreaterThan(5);
        }
    }

    /**
     * R20 A 목표 3 이월 — 폴백 시연 케이스를 없애지 않는다(과업 지시서). 대신 대상을 run id=3(운행
     * 중, 화면에서 가장 눈에 띔)에서 run id=5(확정, 학원 B 격리 검증용)로 옮긴다. 시연은 정확히
     * 한 건이어야 한다 — 둘 이상이면 "폴백이 예외" 라는 인상이 무너지고, 0 건이면 근사 경로 화면을
     * 검증할 수단이 사라진다.
     */
    @Test
    void 폴백_시연은_정확히_한_건이고_run5다() throws SQLException {
        List<String> fallbackRunIds = queryColumn("""
                SELECT cr.run_id FROM route_version rv
                JOIN confirmed_route cr ON cr.current_version_id = rv.id
                WHERE rv.fallback_used = true
                """);
        assertThat(fallbackRunIds).as("fallback_used=true 인 확정 버전이 속한 run_id 목록").containsExactly("5");
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
