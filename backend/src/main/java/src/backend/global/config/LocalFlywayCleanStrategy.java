package src.backend.global.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * {@code local} 기동마다 DB 를 통째로 비우고 스키마·시드를 새로 적재해, Swagger 로 어지럽힌 상태를 항상
 * 초기화된 상태로 되돌린다(`docs/IMPLEMENTATION_PLAN.md §3.2` 요구사항 4). {@code clean()} 은 되돌릴 수 없는
 * 삭제라 이 클래스 하나가 안전장치 5겹 중 2겹(겹②·겹③)을 직접 맡는다 — 나머지(겹①·④·⑤)는
 * {@code @Profile}·{@code application.yml}·가드 테스트로 이 클래스 밖에 있다.
 */
@Component
@Profile("local") // 겹① — local 이 활성 프로파일에 없으면 이 빈 자체가 컨텍스트에 등록되지 않는다.
public class LocalFlywayCleanStrategy implements FlywayMigrationStrategy {

    private static final Set<String> BLOCKED_PROFILES = Set.of("prod", "demo");
    private static final Set<String> ALLOWED_CLEAN_HOSTS = Set.of("localhost", "127.0.0.1");

    /**
     * 겹② — {@code @Profile} 이 실수로 삭제·오탈자가 나도 살아남도록, 활성 프로파일을 여기서
     * 직접 재확인해 위험 프로파일이 섞여 있으면 빈 생성 시점(=컨텍스트 기동 시점)에 실패시킨다.
     */
    public LocalFlywayCleanStrategy(Environment environment) {
        for (String profile : environment.getActiveProfiles()) {
            if (BLOCKED_PROFILES.contains(profile)) {
                throw new IllegalStateException(
                        "local 과 위험 프로파일(" + profile + ")이 함께 활성화됐다 — Flyway clean() 대상이 될 수 없다.");
            }
        }
    }

    /** 데이터소스가 실제로 localhost 를 가리킬 때만 clean() 후 migrate() 한다. 아니면 clean() 을 호출하지 않고 예외를 던진다. */
    @Override
    public void migrate(Flyway flyway) {
        String jdbcUrl = resolveJdbcUrl(flyway.getConfiguration());
        if (!isLocalHost(jdbcUrl)) {
            throw new IllegalStateException(
                    "local 프로파일의 데이터소스가 localhost 를 가리키지 않는다 — clean() 을 거부한다: " + jdbcUrl);
        }
        flyway.clean();
        flyway.migrate();
    }

    /**
     * Flyway 가 {@code spring.datasource.url} 대신 {@code DataSource} 빈으로 연결된 경우
     * (예: Testcontainers 의 {@code @ServiceConnection}) {@link Configuration#getUrl()} 이
     * null 을 반환하므로, 그때는 실제 커넥션의 메타데이터에서 URL 을 다시 읽는다.
     */
    private String resolveJdbcUrl(Configuration configuration) {
        String url = configuration.getUrl();
        if (url != null && !url.isBlank()) {
            return url;
        }
        DataSource dataSource = configuration.getDataSource();
        if (dataSource == null) {
            return null;
        }
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getURL();
        } catch (SQLException e) {
            return null; // 판정 불가능한 형태이므로 안전 쪽(거부)으로 처리한다
        }
    }

    private boolean isLocalHost(String jdbcUrl) {
        if (jdbcUrl == null) {
            return false;
        }
        // 부분 문자열 contains() 판정은 "jdbc:postgresql://prod-db.example.com/x?opt=localhost" 같은
        // 값을 통과시킨다 — 반드시 URI 로 파싱해 host 세그먼트만 비교한다. 파싱 자체가 실패하거나
        // (URISyntaxException) host 를 특정할 수 없는 형태(멀티호스트 페일오버 URL·호스트 생략·
        // Set.of(...) 는 contains(null) 에서 NPE 를 던지므로 null 가드 필수)면 판정 불가능하므로
        // 안전 쪽(거부)으로 처리한다.
        String withoutJdbcPrefix = jdbcUrl.startsWith("jdbc:") ? jdbcUrl.substring("jdbc:".length()) : jdbcUrl;
        try {
            String host = new URI(withoutJdbcPrefix).getHost();
            return host != null && ALLOWED_CLEAN_HOSTS.contains(host);
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
