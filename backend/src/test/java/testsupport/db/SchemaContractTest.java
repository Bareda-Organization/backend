package testsupport.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.mapping.Table;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import jakarta.persistence.Entity;

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

    /**
     * ERD §3 이 정의한 42개 테이블 전수와 정확히 일치해야 한다(Phase 8 신설 run_forced_addition ·
     * F3 S1 신설 delay_notice · F4 S1 신설 run_transfer 포함, Ruling 324 로 link_request 삭제 —
     * 43개→42개). {@code shedlock} 은 도메인 테이블이 아니라 ShedLock 라이브러리가 요구하는 스키마
     * 그대로이지만, {@code docs/ERD.md} 가 이를 별도 그룹 ⑤ 로 직접 문서화하므로(2026-09-03, V4)
     * 42개 안에 포함된다 — 이 대조에서 빠지면 오타난 인프라 테이블 이름도 통과하게 되므로 함께 센다.
     * Flyway 자신의 이력 테이블은 대조 대상 밖이다.
     */
    private static final List<String> ERD_TABLES = List.of(
            // ① 학원 · 계정 · 권한 (7)
            "academy", "academy_setting", "account", "signup_request",
            "academy_staff", "system_admin", "refresh_token",
            // ② 학생 · 보호자 · 주소 (6, Ruling 324 로 link_request 삭제 — 7→6)
            "student", "guardian", "guardian_student",
            "verification_code", "link_code", "weekly_address",
            // ③ 차량 · 인력 · 운행 · 노선 (14)
            "bus", "manager", "stop", "schedule", "route", "route_stop", "run",
            "waypoint", "confirmed_route", "route_version", "run_stop", "run_rider", "assignment",
            "run_forced_addition",
            // ④ 요청 · 예외 · 알림 · 이력 (14, F3 S1 신설 delay_notice · F4 S1 신설 run_transfer 포함)
            "boarding_intent", "change_request", "rider_status_history", "no_show_case",
            "no_show_contact", "emergency_alert", "exception_report", "run_position",
            "notification_log", "device_token", "notification_setting", "audit_log", "delay_notice",
            "run_transfer",
            // ⑤ 라이브러리 인프라 (1)
            "shedlock");

    /** 계정 연결이 승인 시점에 일어나 그 전에는 NULL 인 레코드 3종 (AUTH-11). */
    private static final List<String> ACCOUNT_LINKED_TABLES = List.of("student", "guardian", "manager");

    /**
     * 학원 범위로 직접 조회하는 테이블 — ERD §5.3 끝 문단 · §6.2 "직접 보유 17개". 격리 조건이 모든
     * 쿼리에 붙는 술어라 {@code academy_id} 를 첫 컬럼으로 둔 인덱스(PK·UNIQUE 포함)가 있어야 한다.
     */
    private static final List<String> ACADEMY_SCOPED_TABLES = List.of("academy_setting", "signup_request",
            "academy_staff", "account", "student", "guardian", "manager", "bus", "stop", "schedule", "route", "run",
            "change_request", "notification_log", "audit_log", "exception_report", "emergency_alert");

    /** 엔티티 클래스를 훑는 기준 패키지 — {@code BackendApplication} 의 컴포넌트 스캔 루트와 같다. */
    private static final String ENTITY_BASE_PACKAGE = "src.backend";

    /** 어떤 엔티티도 매핑하지 않는 테이블 — {@code shedlock} 은 라이브러리가 자기 스키마 그대로 관리한다. */
    private static final List<String> ENTITY_UNMAPPED_TABLES = List.of("shedlock");

    @BeforeAll
    static void 스키마_마이그레이션만_적용한다() {
        migrate(SCHEMA_LOCATION);
    }

    @Test
    void V1_을_적용하면_public_스키마의_테이블_집합이_ERD_42개와_정확히_일치한다() throws SQLException {
        List<String> actual = queryColumn("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                  AND table_name <> 'flyway_schema_history'
                """);

        assertThat(actual)
                .as("개수가 아니라 이름 집합으로 대조한다 — 오타난 이름이 42개를 채우면 개수만으로는 통과한다")
                .containsExactlyInAnyOrderElementsOf(ERD_TABLES);
    }

    /**
     * {@code ddl-auto: validate} 는 <b>엔티티가 요구하는 컬럼이 스키마에 없는</b> 방향만 본다 —
     * 반대로 스키마에만 남아 엔티티 어디에도 매핑되지 않은 컬럼(고아 컬럼)은 그 방향으로도,
     * 테이블 집합만 보는 위 테스트로도 걸리지 않는다(R12 §8.15, R13-T2).
     *
     * <p>기대 컬럼 목록은 손으로 옮겨 적지 않고 Hibernate 부트 메타데이터({@link MetadataSources})를
     * 직접 빌드해서 얻는다 — {@code ddl-auto: validate} 가 내부에서 쓰는 것과 같은 모델이라, 엔티티가
     * 늘어도 이 목록이 따로 낡지 않는다. 이 저장소는 모든 {@code @Column}·{@code @JoinColumn}·
     * {@code @Table} 에 이름을 명시하므로(암묵적 네이밍 전략 미사용), Spring Boot 의 네이밍 전략을
     * 재현할 필요가 없다 — Hibernate 가 기본 설정으로 빌드해도 명시한 이름을 그대로 쓴다.
     */
    @Test
    void 엔티티가_매핑하지_않은_컬럼이_스키마에_남아있으면_실패한다() throws SQLException {
        Map<String, Set<String>> expectedColumnsByTable = 엔티티가_매핑한_테이블별_컬럼_목록을_수집한다();

        List<String> actualTables = queryColumn("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                  AND table_name <> 'flyway_schema_history'
                """);
        actualTables.removeAll(ENTITY_UNMAPPED_TABLES);

        for (String table : actualTables) {
            Set<String> expectedColumns = expectedColumnsByTable.get(table);
            assertThat(expectedColumns)
                    .as("%s 테이블을 매핑하는 엔티티가 하나도 없다 — ERD_TABLES 와 엔티티 스캔 결과가 어긋난다", table)
                    .isNotNull();

            Set<String> actualColumns = new HashSet<>(queryColumn("""
                    SELECT column_name FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = '%s'
                    """.formatted(table)));
            Set<String> orphanColumns = new HashSet<>(actualColumns);
            orphanColumns.removeAll(expectedColumns);

            assertThat(orphanColumns)
                    .as("%s 테이블에 어떤 엔티티도 매핑하지 않은 컬럼이 스키마에 남아 있다: %s", table, orphanColumns)
                    .isEmpty();
        }
    }

    /**
     * {@link #ENTITY_BASE_PACKAGE} 아래 {@code @Entity} 전부를 훑어 Hibernate 부트 메타데이터를
     * 한 번에 빌드하고, 테이블 이름별 컬럼 이름 집합으로 접는다. 두 엔티티가 같은 테이블을 매핑해도
     * (예: {@code RunStop}·{@code NavRunStop} 모두 {@code run_stop}) Hibernate 가 같은 이름의
     * {@link Table} 객체 하나로 합쳐 주므로 여기서 따로 병합할 필요가 없다.
     *
     * <p>다이얼렉트 인식용으로 이미 떠 있는 {@link #POSTGRES} 컨테이너에 실제로 접속한다 — 스키마
     * 상태는 건드리지 않고 커넥션 메타데이터만 읽는다(스키마 존재 여부와 무관하게 동작한다).
     */
    private static Map<String, Set<String>> 엔티티가_매핑한_테이블별_컬럼_목록을_수집한다() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));

        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.url", POSTGRES.getJdbcUrl())
                .applySetting("hibernate.connection.username", POSTGRES.getUsername())
                .applySetting("hibernate.connection.password", POSTGRES.getPassword())
                .applySetting("hibernate.connection.driver_class", "org.postgresql.Driver")
                .build();
        try {
            MetadataSources sources = new MetadataSources(registry);
            for (BeanDefinition candidate : scanner.findCandidateComponents(ENTITY_BASE_PACKAGE)) {
                sources.addAnnotatedClassName(candidate.getBeanClassName());
            }

            Map<String, Set<String>> columnsByTable = new HashMap<>();
            for (Table table : sources.buildMetadata().collectTableMappings()) {
                Set<String> columnNames = new HashSet<>();
                table.getColumns().forEach(column -> columnNames.add(column.getName()));
                columnsByTable.put(table.getName(), columnNames);
            }
            return columnsByTable;
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    /**
     * ERD §5.3 "학원 격리 선행 인덱스" 약속을 17개 전수로 대조한다(BR-091) — 빠지면 학원 하나의 목록·검색이
     * 누적 전 행을 읽는다({@code exception_report} 는 무기한 보존인데 인덱스가 0개였다).
     */
    @Test
    void 학원_범위_테이블_17개는_academy_id_를_첫_컬럼으로_둔_인덱스를_가진다() throws SQLException {
        List<String> missing = new ArrayList<>();
        for (String table : ACADEMY_SCOPED_TABLES) {
            List<String> leading = queryColumn("""
                    SELECT indexdef FROM pg_indexes
                    WHERE schemaname = 'public' AND tablename = '%s' AND indexdef LIKE '%% (academy_id%%'
                    """.formatted(table));
            if (leading.isEmpty()) {
                missing.add(table);
            }
        }

        assertThat(missing).as("academy_id 선행 인덱스가 없는 학원 범위 테이블").isEmpty();
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

    /**
     * {@code shedlock} 은 예외다 — ShedLock 의 {@code JdbcTemplateLockProvider}(.usingDbTime())가
     * {@code timezone('utc', CURRENT_TIMESTAMP)} 로 "지금"을 SQL 서버 쪽에서만 계산한다
     * (shedlock-provider-jdbc-template 6.9.2, {@code PostgresSqlServerTimeStatementsSource}).
     * 그 식은 무시간대 {@code timestamp} 값을 낸다 — 컬럼이 {@code timestamptz} 였다면 그 값을 다시
     * {@code timestamptz} 로 암묵 변환하면서 <b>세션 타임존</b>으로 재해석해, PROJECT_NOTES 가 이미
     * 기록한 KST 편차 버그를 프로덕션에 새로 만든다. 그래서 이 테이블만은 라이브러리 기본 스키마
     * (무시간대 {@code TIMESTAMP(3)})를 그대로 둔다.
     */
    @Test
    void V1_이_만든_시각_컬럼에는_오프셋_없는_타입이_하나도_없다() throws SQLException {
        List<String> offsetless = queryColumn("""
                SELECT table_name || '.' || column_name FROM information_schema.columns
                WHERE table_schema = 'public' AND data_type = 'timestamp without time zone'
                  AND table_name <> 'flyway_schema_history'
                  AND table_name <> 'shedlock'
                """);

        assertThat(offsetless)
                .as("오프셋을 버리면 시각 비교 결과만 틀리고 예외는 발생하지 않는다 — 전 컬럼이 timestamptz 여야 한다 "
                        + "(shedlock 은 라이브러리 시간 계산과의 충돌 때문에 예외, 클래스 자바독 참고)")
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
