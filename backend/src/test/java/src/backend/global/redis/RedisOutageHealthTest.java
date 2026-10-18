package src.backend.global.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Consumer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.health.DataRedisHealthContributorAutoConfiguration;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointAutoConfiguration;
import org.springframework.boot.health.autoconfigure.contributor.HealthContributorAutoConfiguration;
import org.springframework.boot.health.autoconfigure.registry.HealthContributorRegistryAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.health.DataSourceHealthContributorAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import testsupport.redis.Outage;
import testsupport.redis.OutageRedis;

/**
 * Redis 가 멈추거나 답하지 않아도 백엔드는 헬스 UP 이다 — 대체 경로로 서비스가 계속되는데 컨테이너 헬스체크({@code /actuator/health})와 외부 감시({@code /healthz})가 DOWN 이면
 * 배포 게이트가 막히고 다시 시작한 백엔드가 건강해지지 못한다(Ruling 932). DB 가 멈추면 반대로 DOWN(503)이어야 한다 — 서비스 전면 정지는 DB 하나뿐이다(TECH_DECISIONS §14).
 * Redis 상태는 헬스가 아니라 {@code RedisFallbackActive} 경보와 Redis 지표로 본다.
 *
 * <p>설정은 {@code application.yml} 을 그대로 읽은 실제 헬스 빈 조합이다.
 */
class RedisOutageHealthTest {

    private static OutageRedis redis;

    @BeforeAll
    static void startRedis() {
        redis = OutageRedis.start();
    }

    @AfterAll
    static void stopRedis() {
        redis.close();
    }

    private void withHealth(String datasourceUrl, Consumer<HealthEndpoint> body) {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(RedisHealthIndicator.class)
                .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class, DataSourceAutoConfiguration.class,
                        HealthContributorRegistryAutoConfiguration.class, HealthContributorAutoConfiguration.class,
                        HealthEndpointAutoConfiguration.class, DataSourceHealthContributorAutoConfiguration.class,
                        DataRedisHealthContributorAutoConfiguration.class))
                .withPropertyValues("spring.data.redis.host=" + redis.host(), "spring.data.redis.port=" + redis.port(),
                        "spring.datasource.hikari.connection-timeout=1000", "spring.datasource.hikari.initialization-fail-timeout=-1");
        if (datasourceUrl != null) {
            runner = runner.withPropertyValues("spring.datasource.url=" + datasourceUrl);
        }
        runner.run((AssertableApplicationContext context) -> body.accept(context.getBean(HealthEndpoint.class)));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Outage.class)
    @DisplayName("Redis 가 멈추거나 답하지 않아도 헬스는 UP — 기본 그룹과 /healthz 용 external 그룹 모두")
    void Redis_장애_중에도_헬스는_UP(Outage outage) {
        withHealth(null, health -> {
            outage.begin(redis);
            try {
                assertThat(health.health().getStatus().getCode()).as("기본 헬스(/actuator/health)").isEqualTo("UP");
                assertThat(health.healthForPath("external").getStatus().getCode()).as("external 그룹(/healthz)").isEqualTo("UP");
                assertThat(health.healthForPath("redis").getStatus().getCode())
                        .as("redis 구성 요소 자체는 UP 이 아니다 — 메인 관리자 시스템 상태 칸(SystemHealthReader)이 이것을 읽는다").isNotEqualTo("UP");
            } finally {
                outage.end(redis);
            }
        });
    }

    @Test
    @DisplayName("Redis 가 정상이면 redis 구성 요소와 전체 헬스가 모두 UP")
    void Redis_정상이면_UP() {
        withHealth(null, health -> {
            assertThat(health.health().getStatus().getCode()).isEqualTo("UP");
            assertThat(health.healthForPath("redis").getStatus().getCode()).isEqualTo("UP");
        });
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Outage.class)
    @DisplayName("DB 에 닿지 못하면 Redis 가 멀쩡해도 헬스는 DOWN — 전면 정지는 DB 하나뿐이다")
    void DB_장애는_헬스를_DOWN_으로_만든다(Outage ignored) {
        withHealth("jdbc:postgresql://localhost:1/none", health -> {
            assertThat(health.health().getStatus().getCode()).as("기본 헬스").isEqualTo("DOWN");
            assertThat(health.healthForPath("external").getStatus().getCode()).as("external 그룹").isEqualTo("DOWN");
        });
    }
}
