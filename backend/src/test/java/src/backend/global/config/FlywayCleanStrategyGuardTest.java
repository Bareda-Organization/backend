package src.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

/**
 * Flyway {@code clean} 안전장치 겹①②③(`docs/IMPLEMENTATION_PLAN.md §3.2`)을 검증한다. 이 클래스 자신이 겹⑤다.
 *
 * <p>기존 {@link DeploymentConfigGuardTest}는 {@code application.yml}을 텍스트로만 읽는
 * 순수 파싱 테스트라 인프라가 이질적이다 — 이 클래스는 {@link ApplicationContextRunner}(빈 등록·
 * 컨텍스트 기동 여부)와 Mockito({@link Flyway} 목)를 쓰므로 별도 클래스로 분리했다
 * (`docs/IMPLEMENTATION_PLAN.md §3.2` 판단 채택). DB·Docker 없이 실행된다.
 */
class FlywayCleanStrategyGuardTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(LocalFlywayCleanStrategy.class);

    @ParameterizedTest(name = "{0} 프로파일 컨텍스트에는 전략 빈이 없다")
    @ValueSource(strings = {"prod", "demo"})
    @DisplayName("겹① — 배포 프로파일 컨텍스트에는 전략 빈이 부재한다")
    void strategyBeanAbsentInDeploymentProfiles(String profile) {
        contextRunner.withPropertyValues("spring.profiles.active=" + profile)
                .run(context -> assertThat(context).doesNotHaveBean(LocalFlywayCleanStrategy.class));
    }

    @DisplayName("겹① — local 프로파일 컨텍스트에는 전략 빈이 존재한다 (반대 방향)")
    @Test
    void strategyBeanPresentInLocalProfile() {
        // ①만 있으면 "빈을 아예 안 만들어도" 통과하므로 반대 방향도 함께 확인한다.
        contextRunner.withPropertyValues("spring.profiles.active=local")
                .run(context -> assertThat(context).hasSingleBean(LocalFlywayCleanStrategy.class));
    }

    @DisplayName("겹② — local 과 위험 프로파일을 병기하면 컨텍스트 기동이 실패한다")
    @Test
    void contextFailsWhenLocalAndProdAreActiveTogether() {
        contextRunner.withPropertyValues("spring.profiles.active=local,prod")
                .run(context -> assertThat(context).hasFailed());
    }

    @DisplayName("겹③ — 원격 데이터소스면 예외를 던지고 clean 을 호출하지 않는다")
    @Test
    void migrateRejectsRemoteDataSourceWithoutCleaning() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        LocalFlywayCleanStrategy strategy = new LocalFlywayCleanStrategy(environment);

        Flyway flyway = mock(Flyway.class);
        Configuration configuration = mock(Configuration.class);
        when(flyway.getConfiguration()).thenReturn(configuration);
        when(configuration.getUrl()).thenReturn("jdbc:postgresql://prod-db.example.com:5432/schoolbus");

        assertThatThrownBy(() -> strategy.migrate(flyway))
                .isInstanceOf(IllegalStateException.class);

        verify(flyway, never()).clean();
    }

    @DisplayName("겹③ — localhost 데이터소스면 clean 을 migrate 보다 먼저 호출한다")
    @Test
    void migrateCleansLocalhostDataSourceInOrder() {
        // 정상 URL 에서도 clean() 이 실제로 불리는지, 그리고 migrate() 보다 먼저인지까지 확인해야
        // "항상 거부하는 구현"도 "순서가 뒤집힌 구현"도 통과하지 못한다.
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        LocalFlywayCleanStrategy strategy = new LocalFlywayCleanStrategy(environment);

        Flyway flyway = mock(Flyway.class);
        Configuration configuration = mock(Configuration.class);
        when(flyway.getConfiguration()).thenReturn(configuration);
        when(configuration.getUrl()).thenReturn("jdbc:postgresql://localhost:5432/schoolbus");

        strategy.migrate(flyway);

        InOrder order = inOrder(flyway);
        order.verify(flyway).clean();
        order.verify(flyway).migrate();
    }

    @DisplayName("겹③(호스트 파싱) — 호스트를 특정할 수 없는 URL 은 NPE 대신 거부 예외를 던진다")
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "jdbc:postgresql://localhost:5432,prod-db.example.com:5432/db",
            "jdbc:postgresql:///schoolbus",
            "jdbc:postgresql://school_bus_db:5432/db",
            "jdbc:h2:mem:testdb"
    })
    void migrateRejectsUnparseableHostWithoutThrowingNpe(String jdbcUrl) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        LocalFlywayCleanStrategy strategy = new LocalFlywayCleanStrategy(environment);

        Flyway flyway = mock(Flyway.class);
        Configuration configuration = mock(Configuration.class);
        when(flyway.getConfiguration()).thenReturn(configuration);
        when(configuration.getUrl()).thenReturn(jdbcUrl);

        assertThatThrownBy(() -> strategy.migrate(flyway))
                .isInstanceOf(IllegalStateException.class);

        verify(flyway, never()).clean();
    }

    @DisplayName("겹③(폴백) — getUrl() 이 null 이고 DataSource 도 없으면 clean 을 거부한다")
    @Test
    void resolveJdbcUrlRejectsWhenDataSourceAbsent() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        LocalFlywayCleanStrategy strategy = new LocalFlywayCleanStrategy(environment);

        Flyway flyway = mock(Flyway.class);
        Configuration configuration = mock(Configuration.class);
        when(flyway.getConfiguration()).thenReturn(configuration);
        when(configuration.getUrl()).thenReturn(null);
        when(configuration.getDataSource()).thenReturn(null);

        assertThatThrownBy(() -> strategy.migrate(flyway))
                .isInstanceOf(IllegalStateException.class);

        verify(flyway, never()).clean();
    }

    @DisplayName("겹③(폴백) — 커넥션 획득이 SQLException 을 던지면 clean 을 거부한다")
    @Test
    void resolveJdbcUrlRejectsWhenConnectionThrows() throws SQLException {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        LocalFlywayCleanStrategy strategy = new LocalFlywayCleanStrategy(environment);

        Flyway flyway = mock(Flyway.class);
        Configuration configuration = mock(Configuration.class);
        DataSource dataSource = mock(DataSource.class);
        when(flyway.getConfiguration()).thenReturn(configuration);
        when(configuration.getUrl()).thenReturn(null);
        when(configuration.getDataSource()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenThrow(new SQLException("connection refused"));

        assertThatThrownBy(() -> strategy.migrate(flyway))
                .isInstanceOf(IllegalStateException.class);

        verify(flyway, never()).clean();
    }

    @DisplayName("겹③(폴백) — getUrl() 이 null 이어도 DataSource 메타데이터가 localhost 면 clean 한다")
    @Test
    void resolveJdbcUrlFallsBackToDataSourceMetadata() throws SQLException {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        LocalFlywayCleanStrategy strategy = new LocalFlywayCleanStrategy(environment);

        Flyway flyway = mock(Flyway.class);
        Configuration configuration = mock(Configuration.class);
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(flyway.getConfiguration()).thenReturn(configuration);
        when(configuration.getUrl()).thenReturn(null);
        when(configuration.getDataSource()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getURL()).thenReturn("jdbc:postgresql://localhost:32768/test");

        strategy.migrate(flyway);

        verify(flyway).clean();
        verify(flyway).migrate();
    }
}
