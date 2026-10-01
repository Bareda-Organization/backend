package src.backend.global.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * R46-FIXOPS 가 운영·스테이징 설정에 넣은 안전장치가 나중에 조용히 되돌아가는 것을 막는 가드.
 *
 * <p>여기서 막는 것은 전부 "앱은 정상 기동하는데 장애 때만 드러나는" 종류다 — OOM 이 나도 프로세스가 안 죽는 것 · CloudWatch 가 느릴 때 로그 쓰기가
 * 앱 스레드를 막는 것 · 배포 때 SIGKILL 이 스프링의 정상 종료보다 먼저 오는 것 · 토큰이 실린 응답이 압축되는 것. 경보 규칙의 발화 조건은
 * {@code alerts.test.yml}(promtool)이 따로 본다 — 이 클래스는 규칙이 있고 시험이 붙어 있는지만 본다.
 *
 * <p>파일 텍스트만 읽으므로 Docker·DB 없이 실행된다. 주석 줄은 걷어 내고 본다 — 주석이 옛 설정 이름을 설명해도 통과하지 않게.
 */
class OpsSettingsGuardTest {

    private static final Path ROOT = Path.of("..");

    /** 스프링 정상 종료 대기의 기본값(Boot 4.1.0 설정 메타데이터 `spring.lifecycle.timeout-per-shutdown-phase`). */
    private static final int SPRING_DEFAULT_SHUTDOWN_SECONDS = 30;

    private static final List<String> NEW_ALERTS = List.of("BackendDown", "Http5xxRatioHigh", "HikariPoolWaiting",
            "CircuitBreakerNotClosed", "SchedulerStalled", "RetentionCleanupStalled");

    @Test
    @DisplayName("OOM 이면 JVM 이 프로세스를 끝낸다 — 운영·스테이징 backend (힙 덤프는 개인정보·디스크 때문에 운영에 없다)")
    void jvmExitsOnOutOfMemory() throws IOException {
        for (String compose : List.of("docker-compose.prod.yml", "docker-compose.staging.yml")) {
            String options = javaToolOptions(serviceBlock(compose, "backend"));
            assertThat(options).as("%s backend JAVA_TOOL_OPTIONS — 스레드만 죽고 JVM 이 남으면 재시작 정책이 못 본다", compose)
                    .contains("-XX:+ExitOnOutOfMemoryError");
        }
        assertThat(javaToolOptions(serviceBlock("docker-compose.prod.yml", "backend")))
                .as("힙 덤프에는 학생·보호자 개인정보와 서명 키가 담기고 힙 크기(약 2.15GB)만큼 루트 디스크를 채운다")
                .doesNotContain("HeapDumpOnOutOfMemoryError");
    }

    @Test
    @DisplayName("운영 로그 드라이버는 non-blocking 이다 — CloudWatch 가 느려도 앱 스레드가 로그 쓰기에서 멈추지 않는다")
    void cloudwatchLogDriverIsNonBlocking() throws IOException {
        String compose = stripComments(read("docker-compose.prod.yml"));
        String anchor = compose.substring(compose.indexOf("x-logging: &cloudwatch"), compose.indexOf("services:"));

        assertThat(anchor).contains("mode: non-blocking").contains("max-buffer-size:");
        assertThat(anchor).as("`awslogs-mode` 는 없는 옵션 이름이다 — 키는 `mode`").doesNotContain("awslogs-mode");
    }

    @Test
    @DisplayName("backend 종료 대기(stop_grace_period)가 스프링 정상 종료 대기보다 길다 — 운영·스테이징")
    void stopGracePeriodOutlastsSpringGracefulShutdown() throws IOException {
        String yml = read("backend/src/main/resources/application.yml");
        assertThat(yml).as("즉시 종료로 바꾸면 이 관계의 전제가 사라진다").doesNotContain("shutdown: immediate");
        int springSeconds = SPRING_DEFAULT_SHUTDOWN_SECONDS;
        Matcher configured = Pattern.compile("timeout-per-shutdown-phase:\\s*(\\d+)s").matcher(yml);
        while (configured.find()) {
            springSeconds = Math.max(springSeconds, Integer.parseInt(configured.group(1)));
        }

        for (String compose : List.of("docker-compose.prod.yml", "docker-compose.staging.yml")) {
            Matcher grace = Pattern.compile("(?m)^\\s+stop_grace_period:\\s*(\\d+)s\\s*$")
                    .matcher(serviceBlock(compose, "backend"));
            assertThat(grace.find()).as("%s backend 에 stop_grace_period 가 있어야 한다(Docker 기본 10초면 스프링보다 먼저 SIGKILL)", compose)
                    .isTrue();
            assertThat(Integer.parseInt(grace.group(1)))
                    .as("%s stop_grace_period(초)는 스프링 종료 대기(%d초)보다 길어야 연결 풀·브로커를 닫을 시간이 남는다", compose, springSeconds)
                    .isGreaterThan(springSeconds);
        }
    }

    @Test
    @DisplayName("postgres 는 쿼리 통계(pg_stat_statements)를 켜고 SSD 용 random_page_cost 를 쓰며 확장은 ops_stats 스키마에 만든다 — 운영·스테이징")
    void postgresCollectsQueryStatsAndTunesForSsd() throws IOException {
        for (String compose : List.of("docker-compose.prod.yml", "docker-compose.staging.yml")) {
            assertThat(serviceBlock(compose, "postgres")).as(compose)
                    .contains("shared_preload_libraries=pg_stat_statements")
                    .contains("random_page_cost=1.1")
                    .contains("./infra/postgres/init:/docker-entrypoint-initdb.d");
        }
        String init = stripComments(read("infra/postgres/init/01-pg-stat-statements.sql"));
        assertThat(init).contains("CREATE EXTENSION IF NOT EXISTS pg_stat_statements SCHEMA ops_stats");
        assertThat(init).as("public 에 만든 확장은 Flyway clean() 이 지운다(스테이징 기동·초기화마다)").doesNotContain("SCHEMA public");
    }

    @Test
    @DisplayName("운영 프록시는 /api/ JSON 만 압축하고 인증 응답(/auth/*)과 연결 코드 발급은 압축하지 않는다(BREACH)")
    void proxyCompressesJsonButNeverTokenResponses() throws IOException {
        String nginx = stripComments(read("infra/proxy/nginx.prod.conf"));

        assertThat(locationBlock(nginx, "location /api/ {")).contains("gzip on;").contains("gzip_types application/json;")
                .contains("gzip_min_length 1024;");
        assertThat(locationBlock(nginx, "location /api/v1/auth/ {")).as("재발급·로그아웃·비밀번호 변경 등 토큰이 실리는 응답")
                .contains("gzip off;");
        assertThat(locationBlock(nginx, "location = /api/v1/me/link-code {")).as("연결 코드 발급 응답").contains("gzip off;");
        assertThat(nginx.split("gzip on;", -1).length - 1).as("압축을 켠 곳은 /api/ 하나뿐이다 — server 수준으로 켜면 로그인 응답까지 압축된다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("백엔드 가용성 경보 6종이 있고 각각 promtool 시험이 붙어 있으며 BackendDown 은 대상이 사라진 경우도 본다")
    void availabilityAlertsExistWithTests() throws IOException {
        String rules = stripComments(read("infra/observability/prometheus/alerts.yml"));
        String tests = stripComments(read("infra/observability/prometheus/alerts.test.yml"));

        for (String alert : NEW_ALERTS) {
            assertThat(rules).as("경보 규칙").contains("- alert: " + alert + "\n");
            assertThat(tests).as("경보 시험").contains("alertname: " + alert + "\n");
        }
        assertThat(rules).as("up == 0 만으로는 스크레이프 대상이 목록에서 사라진 경우를 못 본다")
                .contains("up{job=\"backend\"} == 0 or absent(up{job=\"backend\"})");
    }

    /** 컴포즈 파일의 서비스 하나(다음 서비스 머리나 최상위 키 직전까지) — 주석은 걷어 낸다. */
    private static String serviceBlock(String compose, String service) throws IOException {
        Matcher block = Pattern.compile("(?ms)^ {2}" + service + ":\\n(.*?)(?=^ {2}[a-z][a-z-]*:\\n|^[a-z]+:|\\z)")
                .matcher(stripComments(read(compose)));
        if (!block.find()) {
            throw new AssertionError(compose + " 에서 서비스를 찾지 못했다: " + service);
        }
        return block.group(1);
    }

    /** nginx location 하나 — 시작 줄부터 첫 닫는 중괄호까지(이 파일의 location 은 중첩 블록이 없다). */
    private static String locationBlock(String nginx, String header) {
        int start = nginx.indexOf(header);
        assertThat(start).as("nginx 에 %s 가 있어야 한다", header).isNotNegative();
        return nginx.substring(start, nginx.indexOf('}', start));
    }

    private static String javaToolOptions(String serviceBlock) {
        Matcher options = Pattern.compile("(?m)^\\s+JAVA_TOOL_OPTIONS:\\s*(.+)$").matcher(serviceBlock);
        assertThat(options.find()).as("backend 환경에 JAVA_TOOL_OPTIONS 가 있어야 한다").isTrue();
        return options.group(1);
    }

    private static String read(String relative) throws IOException {
        return Files.readString(ROOT.resolve(relative).normalize());
    }

    /** `#`·`--` 로 시작하는 줄(YAML·nginx 주석 · SQL 주석)을 걷어 낸다. 줄 끝 주석은 이 파일들에서 값 판정에 걸리지 않는다. */
    private static String stripComments(String text) {
        return text.lines().filter(line -> !line.strip().startsWith("#") && !line.strip().startsWith("--"))
                .collect(Collectors.joining("\n"));
    }
}
