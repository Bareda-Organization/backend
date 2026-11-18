package testsupport.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static testsupport.db.TwoAcademyRows.DEPART;
import static testsupport.db.TwoAcademyRows.row;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
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
 * 상태와 그 상태가 요구하는 시각, 좌표 범위·쌍을 DB 가 지키는지 본다 — 어긋난 행은 애플리케이션이 막지 못한 경로(직접 SQL · 시드)로도
 * 체크 위반({@code 23514})으로 거절돼야 한다. 제약마다 그 제약 하나만 어기는 행을 넣고, 위반 메시지에 그 제약 이름이 있는지까지 본다.
 * 정상 행이 들어가는지도 표마다 본다.
 */
class StateAndCoordinateCheckTest extends MigratedPostgresTestBase {

    private static final String CHECK_VIOLATION = "23514";

    private static final BigDecimal INSIDE = new BigDecimal("37.5");

    private static Tenant mine;

    private Connection connection;

    @BeforeAll
    static void 학원과_부모_행을_만든다() throws SQLException {
        migrate(SCHEMA_LOCATION);
        try (Connection c = connection()) {
            mine = TwoAcademyRows.tenant(c, "check");
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

    /** 정상인 한 행 — 표 이름으로 고른다. */
    private static Map<String, Object> valid(String table) {
        Map<String, Object> base = switch (table) {
            case "run" -> TwoAcademyRows.validRun(mine.academy(), mine.bus(), 5);
            case "run_rider", "weekly_address" -> TwoAcademyRows.validChild(table, mine);
            case "change_request" -> row("academy_id", mine.academy(), "run_id", mine.run(), "student_id", mine.student(),
                    "source", "intent", "type", "cancel", "status", "pending", "window_segment", 1,
                    "requested_by", 1, "requested_at", DEPART);
            case "emergency_alert" -> row("academy_id", mine.academy(), "run_id", mine.run(), "bus_no", "1",
                    "raised_by", 1, "raised_by_role", "driver", "type", "accident", "rider_count", 0,
                    "occurred_at", DEPART, "client_key", UUID.randomUUID());
            default -> throw new IllegalArgumentException(table);
        };
        return new HashMap<>(base);
    }

    static Stream<Arguments> 위반_행() {
        BigDecimal over = new BigDecimal("91");
        BigDecimal overLng = new BigDecimal("181");
        return Stream.of(
                Arguments.of("ck_run_started_at", "run", row("status", "moving")),
                Arguments.of("ck_run_finished_at", "run", row("status", "finished", "started_at", DEPART)),
                Arguments.of("ck_run_rider_changed_at", "run_rider", row("status", "boarded")),
                Arguments.of("ck_weekly_address_lat", "weekly_address", row("lat", over, "lng", INSIDE)),
                Arguments.of("ck_weekly_address_lng", "weekly_address", row("lat", INSIDE, "lng", overLng)),
                Arguments.of("ck_weekly_address_coords_paired", "weekly_address", row("lat", INSIDE)),
                Arguments.of("ck_weekly_address_verified", "weekly_address", row("verified", true)),
                Arguments.of("ck_change_request_new_lat", "change_request", row("new_lat", over, "new_lng", INSIDE)),
                Arguments.of("ck_change_request_new_lng", "change_request", row("new_lat", INSIDE, "new_lng", overLng)),
                Arguments.of("ck_change_request_new_coords_paired", "change_request", row("new_lat", INSIDE)),
                Arguments.of("ck_change_request_decided", "change_request", row("status", "approved")),
                Arguments.of("ck_emergency_alert_lat", "emergency_alert", row("lat", over, "lng", INSIDE)),
                Arguments.of("ck_emergency_alert_lng", "emergency_alert", row("lat", INSIDE, "lng", overLng)),
                Arguments.of("ck_emergency_alert_coords_paired", "emergency_alert", row("lat", INSIDE)),
                Arguments.of("ck_emergency_alert_acked", "emergency_alert", row("acked_at", DEPART)));
    }

    @ParameterizedTest(name = "{0} 를 어긴 {1} 행은 거절된다")
    @MethodSource("위반_행")
    void 제약을_어긴_행은_거절된다(String constraint, String table, Map<String, Object> overrides) {
        Map<String, Object> row = valid(table);
        row.putAll(overrides);

        assertThatThrownBy(() -> TwoAcademyRows.insert(connection, table, row))
                .isInstanceOfSatisfying(SQLException.class, e -> {
                    assertThat(e.getSQLState()).as(e.getMessage()).isEqualTo(CHECK_VIOLATION);
                    assertThat(e.getMessage()).contains(constraint);
                });
    }

    @ParameterizedTest(name = "정상 {0} 행은 들어간다")
    @ValueSource(strings = {"run", "run_rider", "weekly_address", "change_request", "emergency_alert"})
    void 정상_행은_들어간다(String table) throws SQLException {
        assertThat(TwoAcademyRows.insert(connection, table, valid(table))).isPositive();
    }

    @ParameterizedTest(name = "상태·시각·좌표가 맞는 {0} 행도 들어간다")
    @MethodSource("맞는_행")
    void 상태에_맞는_시각과_좌표가_있으면_들어간다(String table, Map<String, Object> overrides) throws SQLException {
        Map<String, Object> row = valid(table);
        row.putAll(overrides);

        assertThat(TwoAcademyRows.insert(connection, table, row)).isPositive();
    }

    static Stream<Arguments> 맞는_행() {
        return Stream.of(
                Arguments.of("run", row("status", "finished", "started_at", DEPART, "finished_at", DEPART)),
                Arguments.of("run_rider", row("status", "boarded", "changed_at", DEPART)),
                Arguments.of("weekly_address", row("verified", true, "lat", INSIDE, "lng", INSIDE)),
                Arguments.of("change_request", row("status", "approved", "decided_at", DEPART, "new_lat", INSIDE,
                        "new_lng", INSIDE)),
                Arguments.of("emergency_alert", row("lat", INSIDE, "lng", INSIDE, "acked_at", DEPART, "acked_by", 1)));
    }
}
