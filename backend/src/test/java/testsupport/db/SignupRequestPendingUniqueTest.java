package testsupport.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 계정 하나의 대기(pending) 가입 요청은 DB 가 하나만 받는다 — 승인 대기 중인 계정이 다시 신청을 보내 두 요청이 동시에 대기 상태로 남으면 어느 쪽을
 * 결정해야 하는지 정할 수 없다. 거절·수락으로 끝난 요청은 재신청 이력이라 몇 건이든 남는다.
 */
class SignupRequestPendingUniqueTest extends MigratedPostgresTestBase {

    private static final String UNIQUE_VIOLATION = "23505";

    private static long academy;

    private static long account;

    private Connection connection;

    @BeforeAll
    static void 학원과_계정을_만든다() throws SQLException {
        migrate(SCHEMA_LOCATION);
        try (Connection c = connection()) {
            academy = TwoAcademyRows.insert(c, "academy", TwoAcademyRows.row("code", "signup", "name", "s",
                    "region", "서울", "status", "active"));
            account = TwoAcademyRows.insert(c, "account", TwoAcademyRows.row("academy_id", academy, "login_id", "applicant",
                    "password_hash", "x", "name", "신청자", "phone", "010", "role", "parent", "status", "pending"));
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

    @Test
    void 같은_계정의_두_번째_대기_요청은_거절된다() throws SQLException {
        요청(connection, "pending");

        assertThatThrownBy(() -> 요청(connection, "pending")).isInstanceOfSatisfying(SQLException.class,
                e -> assertThat(e.getSQLState()).as(e.getMessage()).isEqualTo(UNIQUE_VIOLATION));
    }

    @Test
    void 끝난_요청_뒤의_재신청은_들어가고_끝난_요청은_몇_건이든_남는다() throws SQLException {
        요청(connection, "rejected");
        요청(connection, "rejected");
        요청(connection, "accepted");

        assertThat(요청(connection, "pending")).isPositive();
    }

    private static long 요청(Connection c, String status) throws SQLException {
        return TwoAcademyRows.insert(c, "signup_request", Map.of("account_id", account, "academy_id", academy,
                "requested_role", "parent", "approver_type", "staff", "status", status,
                "requested_at", TwoAcademyRows.DEPART));
    }
}
