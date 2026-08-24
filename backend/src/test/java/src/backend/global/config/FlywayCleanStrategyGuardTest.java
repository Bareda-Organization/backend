package src.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

/**
 * Flyway {@code clean} 안전장치 겹①②③⑤(`docs/IMPLEMENTATION_PLAN.md §3.2`)를 검증한다.
 *
 * <p>기존 {@link DeploymentConfigGuardTest}는 {@code application.yml}을 텍스트로만 읽는
 * 순수 파싱 테스트라 인프라가 이질적이다 — 이 클래스는 {@link ApplicationContextRunner}(빈 등록·
 * 컨텍스트 기동 여부)와 Mockito({@link Flyway} 목)를 쓰므로 별도 클래스로 분리했다
 * (`phase1-seed-swagger.md §3` 판단 채택). DB·Docker 없이 실행된다.
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

    @DisplayName("겹② — local 프로파일 컨텍스트에는 전략 빈이 존재한다")
    @Test
    void strategyBeanPresentInLocalProfile() {
        // ①만 있으면 "빈을 아예 안 만들어도" 통과하므로 반대 방향도 함께 확인한다.
        contextRunner.withPropertyValues("spring.profiles.active=local")
                .run(context -> assertThat(context).hasSingleBean(LocalFlywayCleanStrategy.class));
    }

    @DisplayName("겹③ — local 과 위험 프로파일을 병기하면 컨텍스트 기동이 실패한다")
    @Test
    void contextFailsWhenLocalAndProdAreActiveTogether() {
        contextRunner.withPropertyValues("spring.profiles.active=local,prod")
                .run(context -> assertThat(context).hasFailed());
    }

    @DisplayName("겹③(URL 판정) — 원격 데이터소스면 예외를 던지고 clean 을 호출하지 않는다")
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

    @DisplayName("겹③(URL 판정) — localhost 데이터소스면 clean 후 migrate 를 호출한다")
    @Test
    void migrateCleansLocalhostDataSource() {
        // 정상 URL 에서도 clean() 이 실제로 불리는지 확인해야 "항상 거부하는 구현"이 통과하지 못한다.
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        LocalFlywayCleanStrategy strategy = new LocalFlywayCleanStrategy(environment);

        Flyway flyway = mock(Flyway.class);
        Configuration configuration = mock(Configuration.class);
        when(flyway.getConfiguration()).thenReturn(configuration);
        when(configuration.getUrl()).thenReturn("jdbc:postgresql://localhost:5432/schoolbus");

        strategy.migrate(flyway);

        verify(flyway).clean();
        verify(flyway).migrate();
    }
}
