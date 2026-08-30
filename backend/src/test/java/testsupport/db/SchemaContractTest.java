package testsupport.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code V1__init_schema.sql} 이 만든 실제 스키마를 {@code docs/ERD.md} 와 대조한다.
 *
 * <p>마이그레이션 SQL 자체는 TDD 사이클 대상이 아니지만(IMPLEMENTATION_PLAN §4.6.4), 그 대신
 * 이 대조 테스트가 사이클 대상이다 — 단언을 먼저 쓰고 RED 를 확인한 뒤 스키마를 채웠다.
 *
 * <p>여기서 검사하는 4종은 전부 {@code ddl-auto: validate} 가 보지 못하는 것들이다. Hibernate 는
 * 엔티티가 매핑한 테이블·컬럼만 훑으므로 잉여 테이블도, partial 조건도, FK 의 지연 검사 여부도,
 * CHECK 조건식도 검사 대상 밖이다. 이 테스트가 유일한 검출 수단이다.
 *
 * <p>시드({@code db/migration-local})는 일부러 적재하지 않는다 — 검증 대상이 스키마 자체이고,
 * 시드는 별도 태스크의 산출물이라 여기서 함께 적재하면 시드의 결함이 스키마의 결함처럼 보인다.
 */
class SchemaContractTest extends MigratedPostgresTestBase {

    /** ERD §3 이 정의한 40개 테이블 전수(Phase 8 신설 run_forced_addition 포함). Flyway 자신의 이력 테이블은 대조 대상 밖이다. */
    private static final List<String> ERD_TABLES = List.of(
            // ① 학원 · 계정 · 권한 (7)
            "academy", "academy_setting", "account", "signup_request",
            "academy_staff", "system_admin", "refresh_token",
            // ② 학생 · 보호자 · 주소 (7)
            "student", "guardian", "guardian_student", "link_request",
            "verification_code", "link_code", "weekly_address",
            // ③ 차량 · 인력 · 운행 · 노선 (14)
            "bus", "manager", "stop", "schedule", "route", "route_stop", "run",
            "waypoint", "confirmed_route", "route_version", "run_stop", "run_rider", "assignment",
            "run_forced_addition",
            // ④ 요청 · 예외 · 알림 · 이력 (12)
            "boarding_intent", "change_request", "rider_status_history", "no_show_case",
            "no_show_contact", "emergency_alert", "exception_report", "run_position",
            "notification_log", "device_token", "notification_setting", "audit_log");

    /** 계정 연결이 승인 시점에 일어나 그 전에는 NULL 인 레코드 3종 (AUTH-11). */
    private static final List<String> ACCOUNT_LINKED_TABLES = List.of("student", "guardian", "manager");

    @BeforeAll
    static void 스키마_마이그레이션만_적용한다() {
        migrate(SCHEMA_LOCATION);
    }

    @Test
    void V1_을_적용하면_public_스키마의_테이블_집합이_ERD_40개와_정확히_일치한다() throws SQLException {
        List<String> actual = queryColumn("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                  AND table_name <> 'flyway_schema_history'
                """);

        assertThat(actual)
                .as("개수가 아니라 이름 집합으로 대조한다 — 오타난 이름이 40개를 채우면 개수만으로는 통과한다")
                .containsExactlyInAnyOrderElementsOf(ERD_TABLES);
    }

    @Test
    void 계정_연결_레코드_3종의_account_id_UNIQUE_는_account_id_가_있는_행만_대상으로_한다() throws SQLException {
        for (String table : ACCOUNT_LINKED_TABLES) {
            List<String> definitions = queryColumn("""
                    SELECT indexdef FROM pg_indexes
                    WHERE schemaname = 'public' AND tablename = '%s' AND indexdef LIKE '%%(account_id)%%'
                    """.formatted(table));

            assertThat(definitions)
                    .as("%s(account_id) 유니크 인덱스가 1개여야 한다", table)
                    .hasSize(1);
            assertThat(definitions.getFirst())
                    .as("%s(account_id) 는 조건부 UNIQUE 여야 한다 — 일반 UNIQUE 면 계정 미연결 행이 서로 충돌한다", table)
                    .contains("CREATE UNIQUE INDEX")
                    .contains("WHERE (account_id IS NOT NULL)");
        }
    }

    /**
     * 학원당 관계자 1명 정원(C-01 · ACAD-05)이 <b>재직 중인 행만</b> 대상으로 서는지 본다(Ruling 139).
     *
     * <p>조건이 없으면 학원당 {@code academy_staff} 행이 평생 1개라, 퇴사({@code status='inactive'},
     * ACAD-06) 뒤 그 학원은 새 관계자를 영원히 승인할 수 없다 — {@code §6.5} 가 계속
     * {@code 409 STAFF_QUOTA_EXCEEDED} 를 던진다. 정원은 "행이 1개" 가 아니라 "재직자가 1명" 이다.
     *
     * <p>조건식 문자열은 PostgreSQL 이 {@code pg_indexes.indexdef} 로 되돌려 주는 형태를 실측해 적었다 —
     * {@code status} 가 {@code varchar} 라 {@code (status)::text} 로 캐스팅된 형태로 나온다. 손으로
     * 지어낸 형태({@code WHERE (status = 'active')})를 적으면 스키마가 옳아도 실패한다.
     */
    @Test
    void 학원_관계자_정원_UNIQUE_는_재직_중인_행만_대상으로_한다() throws SQLException {
        List<String> definitions = queryColumn("""
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = 'public' AND tablename = 'academy_staff' AND indexdef LIKE '%(academy_id)%'
                """);

        assertThat(definitions)
                .as("academy_staff(academy_id) 유니크 인덱스가 1개여야 한다")
                .hasSize(1);
        assertThat(definitions.getFirst())
                .as("academy_staff(academy_id) 는 status='active' 조건부 UNIQUE 여야 한다 — 조건이 빠지면 퇴사한 학원에 새 관계자를 승인할 수 없다")
                .contains("CREATE UNIQUE INDEX")
                .contains("WHERE ((status)::text = 'active'::text)");
    }

    @Test
    void 확정_노선의_현재_버전_FK_는_지연_검사로_선언된다() throws SQLException {
        List<String> deferral = queryColumn("""
                SELECT c.condeferrable || '/' || c.condeferred
                FROM pg_constraint c
                JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
                WHERE c.conrelid = to_regclass('public.confirmed_route')
                  AND c.contype = 'f' AND a.attname = 'current_version_id'
                """);

        assertThat(deferral)
                .as("confirmed_route.current_version_id 의 FK 가 존재해야 한다 — 순환 참조라 ALTER 로 뒤에 붙인다")
                .hasSize(1);
        assertThat(deferral.getFirst())
                .as("확정 노선과 첫 배포 버전은 같은 트랜잭션에서 생기므로 즉시 검사면 어느 쪽을 먼저 넣어도 실패한다")
                .isEqualTo("true/true");
    }

    @Test
    void 계산식과_배타_조건을_어긴_INSERT_는_해당_CHECK_제약_이름과_함께_거부된다() throws SQLException {
        위반_INSERT_가_제약_이름과_함께_거부되는지_확인한다("ck_bus_student_capacity", connection -> {
            long academyId = SchemaCheckFixtures.insertAcademy(connection);
            execute(connection, """
                    INSERT INTO bus (academy_id, bus_no, plate_no, capacity, driver_count, escort_count, student_capacity)
                    VALUES (%d, '2호차', '34나5678', 25, 1, 1, 25)
                    """.formatted(academyId));
        });
        위반_INSERT_가_제약_이름과_함께_거부되는지_확인한다("ck_run_confirm_at", connection -> {
            long academyId = SchemaCheckFixtures.insertAcademy(connection);
            long busId = SchemaCheckFixtures.insertBus(connection, academyId);
            execute(connection, """
                    INSERT INTO run (academy_id, bus_id, service_date, direction, depart_time, confirm_at,
                                     status, origin_name, destination_name)
                    VALUES (%d, %d, DATE '2026-09-02', 'to_academy',
                            TIMESTAMPTZ '2026-09-02 08:00:00+09', TIMESTAMPTZ '2026-09-02 07:45:00+09',
                            'idle', '중앙로 집결지', '바래다학원')
                    """.formatted(academyId, busId));
        });
        위반_INSERT_가_제약_이름과_함께_거부되는지_확인한다("ck_run_stop_target", connection ->
                execute(connection, 정차_항목_INSERT(connection, "stop_id, waypoint_id", "%d, %d")));
        위반_INSERT_가_제약_이름과_함께_거부되는지_확인한다("ck_run_stop_target", connection ->
                execute(connection, 정차_항목_INSERT(connection, "stop_id, waypoint_id", "NULL, NULL")));
    }

    @Test
    void V1_이_만든_시각_컬럼에는_오프셋_없는_타입이_하나도_없다() throws SQLException {
        List<String> offsetless = queryColumn("""
                SELECT table_name || '.' || column_name FROM information_schema.columns
                WHERE table_schema = 'public' AND data_type = 'timestamp without time zone'
                  AND table_name <> 'flyway_schema_history'
                """);

        assertThat(offsetless)
                .as("오프셋을 버리면 시각 비교 결과만 틀리고 예외는 발생하지 않는다 — 전 컬럼이 timestamptz 여야 한다")
                .isEmpty();
    }

    /**
     * 학생 승하차지와 강제 경유지를 담는 두 컬럼에 넣을 값을 바꿔 가며 정차 항목 INSERT 문을 만든다.
     * 부모 사슬(학원 → 차량 → 회차 → 확정 노선 → 배포 버전)까지 함께 채운다.
     */
    private static String 정차_항목_INSERT(Connection connection, String columns, String values) throws SQLException {
        long academyId = SchemaCheckFixtures.insertAcademy(connection);
        long busId = SchemaCheckFixtures.insertBus(connection, academyId);
        long runId = SchemaCheckFixtures.insertRun(connection, academyId, busId);
        long routeVersionId = SchemaCheckFixtures.insertRouteVersion(connection, runId);
        String resolved = values.contains("%d")
                ? values.formatted(SchemaCheckFixtures.insertStop(connection, academyId),
                        SchemaCheckFixtures.insertWaypoint(connection, runId))
                : values;
        return "INSERT INTO run_stop (route_version_id, %s, seq) VALUES (%d, %s, 1)"
                .formatted(columns, routeVersionId, resolved);
    }

    private static void 위반_INSERT_가_제약_이름과_함께_거부되는지_확인한다(
            String constraintName, ViolatingInsert insert) throws SQLException {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            SQLException rejection = 거부_예외를_받아_둔다(connection, insert);

            // SQLException 은 Iterable<Throwable> 이라 캐스팅 없이는 assertThat 오버로드가 갈리지 않는다.
            assertThat((Throwable) rejection)
                    .as("CHECK 제약 %s 가 위반 행을 거부해야 한다 — 통과하면 미선언이거나 조건식이 다르다", constraintName)
                    .isNotNull();
            assertThat(rejection.getMessage())
                    .as("거부한 주체가 %s 인지 본다 — 준비 INSERT 가 FK·NOT NULL 로 먼저 죽으면 CHECK 를 검증한 것이 아니다",
                            constraintName)
                    .contains(constraintName);
        }
    }

    private static SQLException 거부_예외를_받아_둔다(Connection connection, ViolatingInsert insert)
            throws SQLException {
        try {
            insert.execute(connection);
            return null;
        } catch (SQLException rejection) {
            return rejection;
        } finally {
            connection.rollback();
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
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

    /** 준비 INSERT 와 위반 INSERT 를 한 커넥션 안에서 이어 실행하는 단위. */
    @FunctionalInterface
    private interface ViolatingInsert {

        void execute(Connection connection) throws SQLException;
    }
}
