package testsupport.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 외래 키 칸의 인덱스를 카탈로그에서 읽어 본다 — 부모 행을 지우거나 키를 바꿀 때 DB 는 자식 표에서 그 키를 찾는데, 선두 칸을 앞세운 인덱스나
 * 유일 제약이 없으면 자식 표 전체를 훑는다. 모든 외래 키의 선두 칸이 어떤 인덱스(또는 유일 제약)의 선두여야 한다.
 * {@code IS NOT NULL} 만 조건으로 둔 부분 인덱스는 인정한다(같음 비교는 NULL 을 걸러내므로 외래 키 검사가 그대로 탄다).
 * 아래 예외는 "자식 표가 작거나 부모를 지우지 않아" 인덱스를 두지 않기로 한 칸이다 — 이유가 없으면 목록에 넣지 않는다.
 */
class ForeignKeyIndexCatalogTest extends MigratedPostgresTestBase {

    /** 인덱스가 없는 외래 키 → 두지 않는 이유. */
    private static final Map<String, String> EXCEPTIONS = Map.of(
            "fk_academy_staff_academy", "학원 행은 지우지 않고 inactive 로 둔다 — 지우는 검사가 없다",
            "fk_manager_academy", "학원 행은 지우지 않고 inactive 로 둔다 — 학원 범위 조회는 ix_manager_academy_name 이 받친다",
            "fk_student_academy", "학원 행은 지우지 않고 inactive 로 둔다 — 학원 범위 조회는 ix_student_academy_name 이 받친다",
            "fk_confirmed_route_current_version", "노선 버전은 회차와 함께 무기한 보존한다 — 버전 행을 지우는 경로가 없다",
            "fk_link_code_student", "학생 행은 지우지 않고 익명화한다 — 연결 코드는 만료 인덱스로 따로 정리한다",
            "fk_refresh_token_account", "계정 행은 지우지 않는다 — 계정별 유효 토큰 조회는 부분 인덱스가 받친다");

    private static final String UNINDEXED_FOREIGN_KEYS = """
            SELECT c.conrelid::regclass::text || '.' || a.attname AS fk_column, c.conname
            FROM pg_constraint c
            JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
            WHERE c.contype = 'f' AND c.connamespace = 'public'::regnamespace
              AND NOT EXISTS (
                  SELECT 1 FROM pg_index i
                  WHERE i.indrelid = c.conrelid AND i.indisvalid AND i.indkey[0] = c.conkey[1]
                    AND (i.indpred IS NULL OR pg_get_expr(i.indpred, i.indrelid) = '(' || a.attname || ' IS NOT NULL)'))
            """;

    @BeforeAll
    static void 스키마를_적용한다() {
        migrate(SCHEMA_LOCATION);
    }

    @Test
    void 인덱스가_없는_외래_키_칸은_예외_목록에만_있다() throws SQLException {
        Map<String, String> unindexed = 인덱스_없는_외래_키();

        assertThat(unindexed.keySet()).as("선두 칸에 인덱스가 없는 외래 키(제약 이름) — 인덱스를 만들거나 예외에 이유와 함께 올린다: %s", unindexed)
                .isSubsetOf(EXCEPTIONS.keySet());
    }

    @Test
    void 예외_목록의_외래_키는_여전히_인덱스가_없다() throws SQLException {
        assertThat(EXCEPTIONS.keySet()).as("인덱스가 생긴 외래 키는 예외 목록에서 뺀다").isSubsetOf(인덱스_없는_외래_키().keySet());
    }

    private static Map<String, String> 인덱스_없는_외래_키() throws SQLException {
        Map<String, String> found = new TreeMap<>();
        try (Connection connection = connection(); Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(UNINDEXED_FOREIGN_KEYS)) {
            while (rows.next()) {
                found.put(rows.getString("conname"), rows.getString("fk_column"));
            }
        }
        return found;
    }
}
