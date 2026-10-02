package src.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import com.zaxxer.hikari.HikariDataSource;

/**
 * 운영·demo·staging 프로파일의 Hikari 설정이 <b>실제 DB 연결</b>에 누수 감지와 세션 시간 상한을 건다(R46 T-7 · S-4) — yml 을
 * 프로파일 단위로 읽어 {@code spring.datasource.hikari} 를 {@link HikariDataSource} 에 바인딩하고, 시험 DB 에 연결해
 * {@code SHOW} 로 확인한다. 문자열만 찾는 검사는 키 이름이 틀려도(드라이버가 모르는 속성은 조용히 무시된다) 통과한다.
 *
 * <p>{@code statement_timeout} 은 일부러 걸지 않는다 — 보존 정리 DELETE(5,000행)와 Flyway 마이그레이션이 같은 계정을 쓴다.
 */
class DbSessionTimeoutsTest {

    @ParameterizedTest
    @ValueSource(strings = {"prod", "demo", "staging"})
    @DisplayName("운영·demo·staging 연결은 누수 감지 5초 · lock_timeout 5초 · 유휴 트랜잭션 30초를 갖고 statement_timeout 은 걸지 않는다")
    void 운영_연결에_누수_감지와_세션_시간_상한이_실제로_걸린다(String profile) throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(profileDocument(profile));

        try (HikariDataSource dataSource = new HikariDataSource()) {
            Binder.get(environment).bind("spring.datasource.hikari", Bindable.ofInstance(dataSource));
            // 접속 정보는 시험 DB 로 바꾼다 — 운영 yml 의 ${DB_URL} 은 환경변수라 여기서 풀 수 없다
            dataSource.setJdbcUrl(System.getProperty("spring.datasource.url"));
            dataSource.setUsername("schoolbus");
            dataSource.setPassword("schoolbus");

            assertThat(dataSource.getLeakDetectionThreshold()).as("%s 누수 감지(ms)", profile).isEqualTo(5000);
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                assertThat(show(statement, "lock_timeout")).as("%s lock_timeout", profile).isEqualTo("5s");
                assertThat(show(statement, "idle_in_transaction_session_timeout"))
                        .as("%s idle_in_transaction_session_timeout", profile).isEqualTo("30s");
                assertThat(show(statement, "statement_timeout")).as("%s statement_timeout 은 걸지 않는다", profile)
                        .isEqualTo("0");
            }
        }
    }

    /** {@code application.yml} 의 여러 문서 중 {@code on-profile} 이 일치하는 문서 하나 — 공통 문서의 값은 섞지 않는다. */
    private PropertySource<?> profileDocument(String profile) throws Exception {
        for (PropertySource<?> document : new YamlPropertySourceLoader().load("application",
                new ClassPathResource("application.yml"))) {
            if (profile.equals(document.getProperty("spring.config.activate.on-profile"))) {
                return document;
            }
        }
        throw new AssertionError("프로파일 문서를 찾지 못했다: " + profile);
    }

    private String show(Statement statement, String setting) throws Exception {
        try (ResultSet result = statement.executeQuery("SHOW " + setting)) {
            result.next();
            return result.getString(1);
        }
    }
}
