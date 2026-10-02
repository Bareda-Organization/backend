package testsupport.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.testcontainers.postgresql.PostgreSQLContainer;

import testsupport.TestContainerOwner;

/**
 * Flyway 마이그레이션을 실제 PostgreSQL 컨테이너에 적용한 뒤, 그 결과를 JDBC 로 직접 들여다보는 테스트의 공통 베이스.
 *
 * <p>쓰는 법 — 이 클래스를 상속하고 {@code @BeforeAll} 에서 {@link #migrate(String...)} 를 한 번 호출한다.
 * 스키마만 있는 상태를 보려면 {@link #SCHEMA_LOCATION} 하나를, 시드까지 적재된 상태를 보려면
 * {@link #SCHEMA_LOCATION} 과 {@link #SEED_LOCATION} 을 함께 넘긴다. 시드가 placeholder 를 쓰면
 * {@link #migrate(Map, String...)} 오버로드로 값을 준다. 이후 {@link #connection()} 으로 얻은 커넥션에서
 * 카탈로그(information_schema · pg_catalog) 조회나 실제 INSERT 를 실행한다.
 *
 * <p>Spring 컨텍스트를 띄우지 않고 Flyway 를 직접 호출한다 — 검증 대상이 애플리케이션 빈이 아니라
 * 마이그레이션 SQL 자체이고, 적재 위치를 테스트마다 다르게 줘야 하기 때문이다.
 * {@code spring.flyway.locations} 에 의존하면 프로파일이 더해 주는 시드가 함께 적재돼
 * "스키마만 적용된 상태" 를 관측할 수단이 사라진다.
 *
 * <p>패키지를 {@code src.backend} 트리 밖({@code testsupport}) 에 둔 이유는
 * {@code testsupport.timeaudit.BaseTimeEntityAuditingTest} 와 같다 — {@code BackendApplication} 의
 * 기본 컴포넌트·엔티티 스캔 범위에 테스트 전용 클래스가 섞여 들어가는 것을 막기 위함이다.
 *
 * <p>컨테이너는 {@code RedisTestContainerBase} 와 같은 이유로 JVM 당 1회만 기동한다(BR-149) —
 * 예전에는 {@code @Testcontainers}·{@code @Container} 를 썼는데, 그 조합은 컨테이너 수명을
 * <b>상속한 시험 클래스마다</b> 관리해 14개 클래스가 매번 새 컨테이너를 띄우고 전체 마이그레이션을
 * 다시 적용했다. 정적 초기화로 한 번만 띄우고 클래스별 격리는 {@link #migrate}가 매번 하는
 * {@code flyway.clean() → flyway.migrate()} 로 대신한다 — 그래서 이 클래스를 상속하는 시험은
 * 여전히 자기 {@code @BeforeAll} 에서 {@link #migrate} 를 불러야 한다(컨테이너만 공유, 스키마
 * 상태는 공유하지 않음). 회수는 Ryuk 사이드카가 JVM 종료 시점에 한다.
 */
public abstract class MigratedPostgresTestBase {

    /** 스키마 마이그레이션 위치 — 전 프로파일 공통이며 {@code V1__init_schema.sql} 만 들어 있다. */
    protected static final String SCHEMA_LOCATION = "classpath:db/migration";

    /** 시험 시드 위치 — 시험 JVM 과 {@code fixture} 프로파일에서만 스키마 위치에 더해진다(QA Mock 은 {@code db/qa-seed}). */
    protected static final String SEED_LOCATION = "classpath:db/fixture";

    protected static final PostgreSQLContainer POSTGRES =
            TestContainerOwner.named(new PostgreSQLContainer("postgres:18"), "postgres");

    static {
        POSTGRES.start();
    }

    /** 넘긴 위치의 마이그레이션만 빈 스키마에 적용한다. 이전 호출의 결과는 남지 않는다. */
    protected static void migrate(String... locations) {
        migrate(Map.of(), locations);
    }

    /** placeholder 를 주입해 마이그레이션을 적용한다. 시드가 {@code ${seedPasswordHash}} 같은 값을 요구할 때 쓴다. */
    protected static void migrate(Map<String, String> placeholders, String... locations) {
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(locations)
                .placeholders(placeholders)
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
    }

    /** 마이그레이션이 적용된 데이터베이스로 열린 새 커넥션. 호출자가 닫는다. */
    protected static Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
