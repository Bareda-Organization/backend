package testsupport.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.flywaydb.core.api.Location;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.MutablePropertySources;

/**
 * 첫 배포 뒤 {@code db/migration} 에 {@code V2} 가 생겨도 시험 JVM · {@code fixture} · {@code load} · {@code demo} 설정이 기동하는지 —
 * 실제 설정 파일로 Flyway 만 띄워 {@code V2__probe.sql} 을 더한 채 마이그레이션해 본다.
 *
 * <p>Spring 컨텍스트에는 Flyway 자동 설정만 올린다. 프로파일이 정하는 {@code spring.flyway.*}(위치 · out-of-order · placeholder)가
 * 그대로 적용되고, 시험 JVM 이 주는 시스템 프로퍼티는 시험 JVM 시나리오에서만 살린다.
 */
class SeedMigrationVersionTest extends MigratedPostgresTestBase {

    private static final String SEED_HASH = "$2a$10$Noeszx0nzJUfNo4ubCD03eNfMVfMD9feMo04y/8DHiFLUBF.JZ/Fq";

    @TempDir
    static Path probeDir;

    @BeforeAll
    static void 첫_배포_뒤_생기는_V2_를_흉내_내는_파일을_만든다() throws Exception {
        Files.writeString(probeDir.resolve("V2__probe.sql"), "CREATE TABLE seed_version_probe (id int);\n");
    }

    @BeforeEach
    void 빈_스키마로_시작한다() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA public CASCADE");
            statement.execute("CREATE SCHEMA public");
        }
    }

    @Test
    void 시험_JVM_설정은_V2_가_더해져도_이주된다() throws SQLException {
        List<String> versions = migrate(true, true, null);

        assertThat(versions).contains("1", "2").hasSizeGreaterThan(2);
    }

    @Test
    void fixture_프로파일은_V2_가_더해져도_이주된다() throws SQLException {
        List<String> versions = migrate(true, false, "local,fixture");

        assertThat(versions).contains("1", "2").hasSizeGreaterThan(2);
    }

    @Test
    void load_프로파일은_V2_가_더해져도_이주된다() throws SQLException {
        List<String> versions = migrate(true, false, "load");

        assertThat(versions).contains("1", "2").hasSizeGreaterThan(2);
    }

    @Test
    void QA_시드를_이미_적용한_demo_DB_는_뒤늦게_생긴_V2_를_받고_기동한다() throws SQLException {
        List<String> seeded = migrate(false, false, "demo");
        assertThat(seeded).as("V2 가 없던 시절 demo 가 적용한 버전").containsExactly("1", "900", "901");

        List<String> upgraded = migrate(true, false, "demo");

        assertThat(upgraded).as("V900·V901 뒤에 V2 가 순서를 거슬러 적용돼야 한다").contains("2", "900", "901");
    }

    /** 지정한 설정으로 Flyway 를 한 번 돌리고, 성공으로 적용된 버전을 적용 순서대로 돌려준다. */
    private static List<String> migrate(boolean withProbe, boolean testJvmProperties, String profiles) throws SQLException {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(FlywayOnly.class)
                .web(WebApplicationType.NONE).bannerMode(Banner.Mode.OFF).logStartupInfo(false);
        if (!testJvmProperties) {
            builder.environment(new StandardEnvironment() {
                @Override
                protected void customizePropertySources(MutablePropertySources propertySources) {
                    // 시험 JVM 의 spring.flyway.locations 시스템 프로퍼티와 개발 PC 환경변수를 들이지 않는다 — 프로파일 값만 본다.
                }
            });
        }
        List<String> args = new ArrayList<>(List.of(
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.flyway.placeholders.seedPasswordHash=" + SEED_HASH,
                "--SEED_PASSWORD_HASH=" + SEED_HASH));
        if (profiles != null) {
            args.add("--spring.profiles.active=" + profiles);
        }
        if (withProbe) {
            args.add("--probe.location=filesystem:" + probeDir);
        }
        builder.run(args.toArray(String[]::new)).close();
        return appliedVersions();
    }

    private static List<String> appliedVersions() throws SQLException {
        List<String> versions = new ArrayList<>();
        try (Connection connection = connection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank")) {
            while (rows.next()) {
                versions.add(rows.getString(1));
            }
        }
        return versions;
    }

    /** Flyway 자동 설정만 올리고, {@code probe.location} 이 있으면 설정이 준 위치에 그 위치를 더한다. */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(FlywayAutoConfiguration.class)
    static class FlywayOnly {

        @Bean
        FlywayConfigurationCustomizer probeLocation(@Value("${probe.location:}") String probeLocation) {
            return configuration -> {
                if (!probeLocation.isBlank()) {
                    configuration.locations(Stream.concat(Arrays.stream(configuration.getLocations()),
                            Stream.of(new Location(probeLocation))).toArray(Location[]::new));
                }
            };
        }
    }
}
