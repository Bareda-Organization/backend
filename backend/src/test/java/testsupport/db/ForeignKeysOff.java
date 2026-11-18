package testsupport.db;

import java.sql.PreparedStatement;
import java.sql.Statement;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 외래 키 검사를 끄고 행 하나를 넣는다 — 복합 FK 가 막는 "학원을 넘는 연결" 을 일부러 만들어, 조회 쪽 학원 조건이 그런 행을 걸러내는지(방어선이
 * 두 겹인지) 시험할 때 쓴다. 같은 연결에서 {@code session_replication_role} 을 바꿨다가 되돌리므로 다른 시험에 새지 않는다.
 */
public final class ForeignKeysOff {

    private ForeignKeysOff() {
    }

    /** 검사 없이 {@code sql}(INSERT) 한 문장을 실행하고 영향받은 행 수를 돌려준다. */
    public static int update(JdbcTemplate jdbc, String sql, Object... args) {
        return jdbc.execute((ConnectionCallback<Integer>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET session_replication_role = replica");
            }
            try (PreparedStatement insert = connection.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    insert.setObject(i + 1, args[i]);
                }
                return insert.executeUpdate();
            } finally {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET session_replication_role = DEFAULT");
                }
            }
        });
    }
}
