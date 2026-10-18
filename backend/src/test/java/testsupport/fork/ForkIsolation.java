package testsupport.fork;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 시험 JVM(그레이들 워커 = 포크)마다 DB 를 하나씩 둔다 — 포크끼리 같은 테이블 행 · 같은 시드 ID 를 밟지 않는다.
 * 전용 DB 이름 뒤에 워커 번호를 붙인 DB 를 만들고(없을 때만) 이 JVM 의 {@code spring.datasource.url} 로 바꾼다. 사진 폴더도 같은 번호로 가른다.
 * Redis 는 JVM 마다 컨테이너가 하나라(testsupport.redis) 따로 가를 것이 없다.
 */
final class ForkIsolation {

    private static final String DEFAULT_ACCOUNT = "schoolbus";

    private ForkIsolation() {
    }

    /** {@code jdbc:postgresql://호스트:포트/이름?옵션} 의 이름 뒤에 {@code _w<워커 번호>} 를 붙인다. */
    static String forkUrl(String url, String worker) {
        int query = url.indexOf('?');
        String base = query < 0 ? url : url.substring(0, query);
        String options = query < 0 ? "" : url.substring(query);
        return base + "_w" + worker + options;
    }

    /** DB 를 만들 관리 연결 — 같은 서버의 {@code postgres} DB. */
    static String adminUrl(String url) {
        int query = url.indexOf('?');
        String base = query < 0 ? url : url.substring(0, query);
        String options = query < 0 ? "" : url.substring(query);
        return base.substring(0, base.lastIndexOf('/') + 1) + "postgres" + options;
    }

    /** 이 JVM 을 포크 전용 DB · 사진 폴더로 돌린다 — 그레이들 워커가 아니거나 DB 주소가 없으면 아무것도 하지 않는다. */
    static void isolateThisJvm() throws SQLException {
        String worker = System.getProperty("org.gradle.test.worker");
        String url = System.getProperty("spring.datasource.url");
        if (worker == null || url == null) {
            return;
        }
        String forkUrl = forkUrl(url, worker);
        createDatabaseIfAbsent(adminUrl(url), forkUrl.substring(forkUrl.lastIndexOf('/') + 1).replaceAll("\\?.*$", ""));
        System.setProperty("spring.datasource.url", forkUrl);
        String photoRoot = System.getProperty("app.photo.local.root");
        if (photoRoot != null) {
            System.setProperty("app.photo.local.root", photoRoot + "/w" + worker);
        }
        System.out.println("[fork-isolation] worker=" + worker + " db=" + forkUrl.substring(forkUrl.lastIndexOf('/') + 1));
    }

    private static void createDatabaseIfAbsent(String adminUrl, String database) throws SQLException {
        String user = System.getProperty("spring.datasource.username", System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", DEFAULT_ACCOUNT));
        String password = System.getProperty("spring.datasource.password", System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", DEFAULT_ACCOUNT));
        try (Connection connection = DriverManager.getConnection(adminUrl, user, password)) {
            try (PreparedStatement exists = connection.prepareStatement("select 1 from pg_database where datname = ?")) {
                exists.setString(1, database);
                try (ResultSet rows = exists.executeQuery()) {
                    if (rows.next()) {
                        return;
                    }
                }
            }
            try (var statement = connection.createStatement()) {
                statement.execute("create database \"" + database + "\"");
            }
        }
    }
}
