package src.backend.global.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import src.backend.exception.scheduler.NoShowEscalationScheduler;
import src.backend.observability.metrics.RefreshTokenRowsMetrics;
import src.backend.observability.metrics.SchedulerHealthMetrics;
import src.backend.schedule.scheduler.DailyRunGenerator;

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
    @DisplayName("deploy.sh 는 proxy 를 다시 시작하기 전에 새 nginx 설정을 nginx -t 로 검사하고, 검사가 실패하면 배포를 멈춘다")
    void deployChecksNginxConfigBeforeRestartingProxy() throws IOException {
        String deploy = stripComments(read("infra/scripts/deploy.sh"));
        int up = deploy.indexOf("$COMPOSE up -d");
        int check = deploy.indexOf("$COMPOSE run --rm --no-deps -T proxy nginx -t");
        int restart = deploy.indexOf("$COMPOSE restart proxy");

        assertThat(check).as("새 컨테이너로 하는 검사가 있어야 한다 — 설정 파일 하나를 마운트한 proxy 는 교체된 새 파일을 못 본다").isNotNegative();
        assertThat(up).as("검사는 backend 가 뜬 뒤에 한다 — 아니면 upstream 이름(backend)을 못 풀어 정상 설정도 실패한다").isNotNegative().isLessThan(check);
        assertThat(restart).as("검사를 통과한 설정을 proxy 에 반영하는 재시작이 있어야 한다").isGreaterThan(check);
        assertThat(deploy.substring(check, restart)).as("검사 실패 시 재시작에 닿기 전에 배포를 실패로 끝낸다(proxy 가 못 뜨면 API 전체가 멈춘다)")
                .contains("exit 1");
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

    @Test
    @DisplayName("alerts.yml 의 모든 경보 규칙에 promtool 시험 사례(alertname)가 있다 — 일부 규칙만 시험하면 나머지 식은 고쳐도 아무도 모른다(BR-329)")
    void everyAlertRuleHasAPromtoolCase() throws IOException {
        String rules = stripComments(read("infra/observability/prometheus/alerts.yml"));
        String tests = stripComments(read("infra/observability/prometheus/alerts.test.yml"));

        Matcher rule = Pattern.compile("(?m)^\\s+- alert: (\\w+)\\s*$").matcher(rules);
        Set<String> untested = new TreeSet<>();
        int count = 0;
        while (rule.find()) {
            count++;
            if (!Pattern.compile("(?m)^\\s+alertname: " + rule.group(1) + "\\s*$").matcher(tests).find()) {
                untested.add(rule.group(1));
            }
        }
        assertThat(count).as("alerts.yml 에서 찾은 규칙 수 — 0 이면 이 검사가 아무것도 못 본 것이다").isGreaterThan(10);
        assertThat(untested).as("promtool 사례(alerts.test.yml 의 alertname)가 없는 규칙").isEmpty();
    }

    @Test
    @DisplayName("일일 회차 생성은 실패 경보와 25시간 정지 경보가 있고, 경보의 스케줄러 이름이 코드의 계측 이름과 같다 — 이름이 어긋나면 값 없이 영영 조용하다(BR-333)")
    void dailyRunGenerationIsWatchedUnderTheNameTheCodeEmits() throws IOException {
        String rules = stripComments(read("infra/observability/prometheus/alerts.yml"));
        String generator = SchedulerHealthMetrics.nameOf(DailyRunGenerator.class);
        String noShow = SchedulerHealthMetrics.nameOf(NoShowEscalationScheduler.class);

        assertThat(rules).contains("increase(schoolbus_scheduler_failures_total{job=\"backend\",scheduler=\"" + generator + "\"}[1h]) > 0")
                .contains("schoolbus_scheduler_last_success_age_seconds{job=\"backend\",scheduler=\"" + generator + "\"} > 90000");
        assertThat(rules).as("미승차 에스컬레이션 경보도 계측 이름(%s)을 쓴다", noShow)
                .contains("schoolbus_scheduler_failures_total{scheduler=\"" + noShow + "\"}")
                .contains("schoolbus_scheduler_last_success_age_seconds{scheduler=\"" + noShow + "\"}");
    }

    @Test
    @DisplayName("refresh_token 남은 행 수 게이지를 읽는 대시보드 패널이 있다 — 하루 1회 갱신이라 사람이 7일 이상 시계열을 열어 볼 자리가 있어야 한다(BR-350)")
    void refreshTokenRowsGaugeHasADashboardPanel() throws IOException {
        MeterRegistry registry = new SimpleMeterRegistry();
        new RefreshTokenRowsMetrics(registry);
        // Prometheus 이름은 점을 밑줄로 바꾼 것이다(게이지라 접미사 없음) — 코드의 이름이 바뀌면 패널이 "데이터 없음" 으로 조용해지지 않게 코드에서 도출한다.
        String exported = registry.getMeters().get(0).getId().getName().replace('.', '_');

        assertThat(exported).isEqualTo("schoolbus_refresh_token_rows");
        assertThat(read("infra/observability/grafana/dashboards/3-data.json")).as("데이터 계층 대시보드에 이 게이지를 그리는 패널").contains("\"expr\": \"" + exported + "\"");
    }

    @Test
    @DisplayName("certbot 컨테이너는 갱신 루프(renew-loop.sh)를 돌며 성공 시각을 textfile 폴더에 쓰고, 경보 CertbotRenewStale 이 같은 지표 이름을 2일 기준으로 본다 — BR-334")
    void certbotRenewalSuccessIsExportedAndAlertedOn() throws IOException {
        String certbot = serviceBlock("docker-compose.prod.yml", "certbot");
        assertThat(certbot).as("갱신 루프 스크립트를 마운트해 entrypoint 로 돈다")
                .contains("./infra/certbot/renew-loop.sh:/renew-loop.sh:ro").contains("/renew-loop.sh");
        assertThat(certbot).as("backup-db.sh 와 같은 textfile 폴더를 쓰기 가능으로 마운트한다(node-exporter 는 같은 폴더를 읽기 전용으로 읽는다)")
                .contains("- /var/lib/node_exporter/textfile:/textfile\n").doesNotContain("/textfile:ro");
        assertThat(read("infra/certbot/renew-loop.sh")).as("스크립트의 기본 지표 폴더가 컨테이너 안 마운트 위치와 같다")
                .contains("TEXTFILE_DIR=\"${TEXTFILE_DIR:-/textfile}\"");

        String metric = "schoolbus_certbot_renew_last_success_timestamp_seconds";
        assertThat(read("infra/certbot/renew-loop.sh")).contains(metric);
        String rules = stripComments(read("infra/observability/prometheus/alerts.yml"));
        assertThat(rules).contains("- alert: CertbotRenewStale\n")
                .contains("(time() - " + metric + " > 172800) or absent(" + metric + ")");
    }

    @Test
    @DisplayName("STOMP 세션 접근 경보는 시험이 붙어 있고 임계가 운영 동시 연결 상한(max-connections)의 75% 다 — 상한만 바꾸면 실패한다")
    void stompSessionsAlertTracksConnectionCap() throws IOException {
        String rules = stripComments(read("infra/observability/prometheus/alerts.yml"));
        String tests = stripComments(read("infra/observability/prometheus/alerts.test.yml"));

        assertThat(rules).as("경보 규칙").contains("- alert: StompSessionsNearCap\n");
        assertThat(tests).as("경보 시험").contains("alertname: StompSessionsNearCap\n");

        Matcher threshold = Pattern.compile("schoolbus_stomp_sessions\\{job=\"backend\"\\}\\s*>\\s*(\\d+)").matcher(rules);
        assertThat(threshold.find()).as("job=\"backend\" 로 걸러야 상한을 일부러 넘겨 한계를 재는 부하 시험 프로파일에 울리지 않는다").isTrue();
        assertThat(Integer.parseInt(threshold.group(1)))
                .as("임계는 운영 max-connections(%d)의 75% — 상한에 닿기 전 4분의 1 이 남았을 때 알린다", prodMaxConnections())
                .isEqualTo(prodMaxConnections() * 3 / 4);
    }

    @Test
    @DisplayName("디스크 80% 경보는 루트와 데이터 디스크(bootstrap-ec2.sh 의 DOCKER_VOLUMES)를 함께 보고, node-exporter 는 그 마운트 지점을 버리지 않는다 — BR-305")
    void diskAlertWatchesTheDataDiskAndExporterKeepsItsSeries() throws IOException {
        Matcher mount = Pattern.compile("(?m)^DOCKER_VOLUMES=(\\S+)$").matcher(read("infra/scripts/bootstrap-ec2.sh"));
        assertThat(mount.find()).as("bootstrap-ec2.sh 에 DOCKER_VOLUMES 가 있어야 한다").isTrue();
        String dataDisk = mount.group(1);

        // PromQL 의 `=~` 는 값 전체가 일치해야 하므로 Java 의 matches 와 같다.
        String rules = stripComments(read("infra/observability/prometheus/alerts.yml"));
        Matcher selector = Pattern.compile("(?s)- alert: HostDiskAlmostFull\\n\\s+expr:[^\\n]*?node_filesystem_avail_bytes\\{mountpoint=~\\\"([^\\\"]+)\\\"\\}")
                .matcher(rules);
        assertThat(selector.find()).as("HostDiskAlmostFull 은 mountpoint=~\"…\" 로 마운트 지점을 고른다(루트만 보는 mountpoint=\"/\" 가 아니다)").isTrue();
        assertThat(Pattern.matches(selector.group(1), "/")).as("루트 디스크").isTrue();
        assertThat(Pattern.matches(selector.group(1), dataDisk)).as("DB·사진·지표가 있는 데이터 디스크 %s", dataDisk).isTrue();

        // node-exporter 는 기본값으로 /var/lib/docker/ 아래 마운트를 전부 버린다 — 데이터 디스크는 그 아래라 시계열이 아예 없다(v1.12.1 기본 정규식).
        Matcher exclude = Pattern.compile("--collector\\.filesystem\\.mount-points-exclude=(\\S+)")
                .matcher(serviceBlock("docker-compose.prod.yml", "node-exporter"));
        assertThat(exclude.find()).as("기본 제외 규칙(var/lib/docker/.+)이 데이터 디스크를 버리므로 node-exporter 에 재정의가 있어야 한다").isTrue();
        Pattern excluded = Pattern.compile(exclude.group(1).replace("$$", "$")); // compose 에서 `$$` 는 문자 그대로의 `$`
        assertThat(excluded.matcher(dataDisk).find()).as("제외 규칙이 데이터 디스크 %s 를 버리면 경보 식이 볼 시계열이 없다", dataDisk).isFalse();
        assertThat(excluded.matcher("/proc").find()).as("가상 파일시스템은 계속 버린다").isTrue();
        assertThat(excluded.matcher("/var/lib/docker/containers/abc/mounts/shm").find()).as("컨테이너별 마운트는 계속 버린다").isTrue();
    }

    @Test
    @DisplayName("프록시(nginx)의 워커당 연결 한도는 Tomcat 동시 연결 상한의 2배 이상이다 — 프록시한 WebSocket 1개가 클라이언트·backend 연결 2개를 쓴다 — BR-328")
    void proxyConnectionLimitCoversTheTomcatCap() throws IOException {
        String main = stripComments(read("infra/proxy/nginx.main.prod.conf"));
        int workerConnections = directive(main, "worker_connections");
        int openFiles = directive(main, "worker_rlimit_nofile");

        // 기본값(worker_connections 1024)이면 2 vCPU 에서 동시 세션 약 1,000개에서 새 연결(REST 포함)을 버린다 — backend 는 그 연결을 본 적이 없어 STOMP 경보도 안 울린다.
        // 워커마다 따로 세므로(연결이 한 워커에 몰릴 수 있다) 코어 수와 무관하게 워커 하나가 상한을 담아야 한다.
        assertThat(workerConnections).as("worker_connections — Tomcat max-connections(%d)의 2배(클라이언트 쪽 + backend 쪽) 이상", prodMaxConnections())
                .isGreaterThanOrEqualTo(2 * prodMaxConnections());
        assertThat(openFiles).as("worker_rlimit_nofile — 연결마다 파일 기술자 1개이므로 워커 연결 한도의 2배 이상(로그·소켓 여유)")
                .isGreaterThanOrEqualTo(2 * workerConnections);
        assertThat(main).as("server 블록이 든 default.conf 를 이 전역 파일이 읽어야 한다").contains("include /etc/nginx/conf.d/*.conf;");

        String proxy = serviceBlock("docker-compose.prod.yml", "proxy");
        assertThat(proxy).as("전역 설정 파일을 /etc/nginx/nginx.conf 로 마운트해야 이미지 기본값(1024)이 바뀐다")
                .contains("./infra/proxy/nginx.main.prod.conf:/etc/nginx/nginx.conf:ro");
        Matcher hardLimit = Pattern.compile("(?s)nofile:.*?hard:\\s*(\\d+)").matcher(proxy);
        assertThat(hardLimit.find()).as("컨테이너 파일 기술자 상한(ulimits.nofile.hard)을 명시한다 — 호스트 기본값에 기대지 않는다").isTrue();
        assertThat(Integer.parseInt(hardLimit.group(1))).as("ulimits.nofile.hard 는 worker_rlimit_nofile 이상이어야 nginx 가 그 값까지 올릴 수 있다")
                .isGreaterThanOrEqualTo(openFiles);
    }

    @Test
    @DisplayName("개발 오버레이·스테이징 compose 의 backend 에 healthcheck 가 있고 그 프로파일의 헬스 포트를 친다 — proxy 는 backend 가 healthy 가 된 뒤 시작한다(운영과 같다)")
    void devAndStagingBackendHaveAHealthcheckThatProxyWaitsFor() throws IOException {
        // 이 이미지에 curl 이 있어야 healthcheck 가 돈다 — 없으면 영원히 unhealthy 라 proxy 가 못 뜬다.
        assertThat(read("backend/Dockerfile")).as("런타임 이미지에 curl 을 설치한다(wget 은 없다)").contains("apt-get install -y --no-install-recommends curl");

        for (String[] target : new String[][] {{"docker-compose.app.yml", "local"}, {"docker-compose.staging.yml", "staging"}}) {
            String compose = target[0];
            int port = healthPortOf(target[1]);
            String backend = serviceBlock(compose, "backend");

            assertThat(backend).as("%s backend — %s 프로파일의 헬스 포트(%d)를 친다", compose, target[1], port)
                    .contains("test: [\"CMD-SHELL\", \"curl -fsS http://localhost:" + port + "/actuator/health || exit 1\"]")
                    .contains("start_period:").contains("retries:");
            assertThat(serviceBlock(compose, "proxy")).as("%s proxy — backend 가 healthy 가 되기 전에는 시작하지 않는다(그래야 502 구간과 죽은 backend 가 up 단계에서 드러난다)", compose)
                    .containsPattern("(?s)depends_on:.*?backend:\\s+condition: service_healthy");
        }
    }

    /** 그 프로파일 문서에 {@code management.server.port} 가 있으면 그 값, 없으면 앱 포트(8080) — local 은 관리 포트를 따로 열지 않는다. */
    private static int healthPortOf(String profile) throws IOException {
        for (String document : read("backend/src/main/resources/application.yml").split("(?m)^---\\s*$")) {
            if (Pattern.compile("(?m)^\\s+on-profile:\\s*" + profile + "\\s*$").matcher(document).find()) {
                Matcher port = Pattern.compile("(?m)^management:\\s*\\n\\s+server:\\s*\\n\\s+port:\\s*(\\d+)").matcher(stripComments(document));
                return port.find() ? Integer.parseInt(port.group(1)) : 8080;
            }
        }
        throw new AssertionError("application.yml 에 " + profile + " 프로파일 문서가 없다");
    }

    /** {@code application.yml} 의 {@code on-profile: prod} 문서에 명시된 {@code server.tomcat.max-connections}. */
    private static int prodMaxConnections() throws IOException {
        for (String document : read("backend/src/main/resources/application.yml").split("(?m)^---\\s*$")) {
            if (Pattern.compile("(?m)^\\s+on-profile:\\s*prod\\s*$").matcher(document).find()) {
                Matcher cap = Pattern.compile("(?m)^\\s+max-connections:\\s*(\\d+)").matcher(stripComments(document));
                assertThat(cap.find()).as("prod 문서에 server.tomcat.max-connections 가 명시돼 있어야 한다").isTrue();
                return Integer.parseInt(cap.group(1));
            }
        }
        throw new AssertionError("application.yml 에 prod 프로파일 문서가 없다");
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

    /** nginx 전역 지시자 {@code <이름> <숫자>;} 의 숫자. */
    private static int directive(String nginx, String name) {
        Matcher value = Pattern.compile("(?m)^\\s*" + name + "\\s+(\\d+)\\s*;").matcher(nginx);
        assertThat(value.find()).as("nginx 전역 설정에 %s 가 있어야 한다", name).isTrue();
        return Integer.parseInt(value.group(1));
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
