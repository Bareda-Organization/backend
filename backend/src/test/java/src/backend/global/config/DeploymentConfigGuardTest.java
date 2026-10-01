package src.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 배포 설정이 위험한 기본값으로 되돌아가는 것을 막는 가드.
 *
 * <p>여기서 막는 사고는 전부 "조용히 성공하는" 종류다 — 앱은 정상 기동하지만
 * 공개 도메인에 약한 계정이나 개발용 설정이 열린 채로 뜬다. 테스트가 없으면
 * 리뷰에서 놓치고 배포 후에야 드러난다.
 *
 * <p>파일 텍스트만 읽으므로 Docker·DB 없이 실행된다.
 */
class DeploymentConfigGuardTest {

    /**
     * yml 이 읽지만 운영 compose 가 일부러 넘기지 않는 환경변수 — 이유가 없으면 여기 넣지 않는다.
     * 넘기는 쪽이 늘 맞다(안 넘기면 운영은 yml 기본값으로 조용히 뜬다).
     */
    private static final Set<String> COMPOSE_EXEMPT_ENV = Set.of(
            // 지오코딩·경로 키의 우선 이름이다 — 운영은 fallback 이름(NAVER_DIRECTIONS_KEY_ID · NAVER_DIRECTIONS_KEY)
            // 하나로 두 API 를 함께 쓴다(application.yml `${NAVER_MAPS_KEY_ID:${NAVER_DIRECTIONS_KEY_ID:}}`).
            "NAVER_MAPS_KEY_ID", "NAVER_MAPS_KEY");

    /**
     * 운영 compose backend 가 넘기지만 yml 의 {@code ${…}} 에는 없는 이름 — 스프링·JVM 이 직접 읽는 것만 둔다.
     * 그 밖의 이름이 여기서 걸리면 아무도 읽지 않는 죽은 설정이다(R46 ops 보고: {@code ROUTING_PROVIDER} 를 넘기는데
     * 읽는 곳이 없어 "{@code osrm} 로 우회" 안내가 효과가 없었다).
     */
    private static final Set<String> PLATFORM_READ_ENV = Set.of("SPRING_PROFILES_ACTIVE", "JAVA_TOOL_OPTIONS");

    private static String applicationYml;

    private static String prodCompose;

    private static String deployScript;

    @BeforeAll
    static void readConfig() throws IOException {
        applicationYml = Files.readString(Path.of("src/main/resources/application.yml"));
        prodCompose = Files.readString(Path.of("../docker-compose.prod.yml"));
        deployScript = Files.readString(Path.of("../infra/scripts/deploy.sh"));
    }

    @Test
    @DisplayName("R46 ops C #4 — application.yml 이 읽는 환경변수는 운영 compose 의 backend 가 전부 넘긴다(의도적 제외 목록만 예외)")
    void prodComposePassesEveryEnvVarTheYmlReads() {
        // 안 넘기면 운영은 yml 기본값으로 조용히 뜬다 — 네이버 Directions 한도 전환(Ruling 361) · 장소 검색 키 ·
        // 사진 저장 경로를 운영에서 못 바꾸는 상태가 실제로 있었다. 목록을 손으로 적지 않고 yml 에서 뽑는다.
        Set<String> ymlEnv = new TreeSet<>();
        Matcher yml = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)").matcher(applicationYml);
        while (yml.find()) {
            ymlEnv.add(yml.group(1));
        }
        Set<String> composeEnv = new TreeSet<>();
        Matcher compose = Pattern.compile("(?m)^ {6}([A-Z][A-Z0-9_]*):").matcher(composeServiceBlock("backend"));
        while (compose.find()) {
            composeEnv.add(compose.group(1));
        }

        ymlEnv.removeAll(composeEnv);
        ymlEnv.removeAll(COMPOSE_EXEMPT_ENV);
        assertThat(ymlEnv).as("yml 이 읽는데 운영 compose backend 가 넘기지 않는 환경변수").isEmpty();
    }

    @Test
    @DisplayName("R46 FUBE — 운영 compose backend 가 넘기는 환경변수는 yml 이 전부 읽는다(죽은 설정 금지)")
    void prodComposePassesNoEnvVarNobodyReads() {
        Set<String> ymlEnv = new TreeSet<>();
        Matcher yml = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)").matcher(applicationYml);
        while (yml.find()) {
            ymlEnv.add(yml.group(1));
        }
        Set<String> composeEnv = new TreeSet<>();
        Matcher compose = Pattern.compile("(?m)^ {6}([A-Z][A-Z0-9_]*):").matcher(composeServiceBlock("backend"));
        while (compose.find()) {
            composeEnv.add(compose.group(1));
        }

        composeEnv.removeAll(ymlEnv);
        composeEnv.removeAll(PLATFORM_READ_ENV);
        assertThat(composeEnv).as("운영 compose backend 가 넘기는데 yml 이 읽지 않는 환경변수").isEmpty();
    }

    @Test
    @DisplayName("R46 FUBE — deploy.sh 가 .env 에 쓰는 이름은 운영 compose 가 전부 쓴다(쓰지 않는 값을 SSM 필수로 요구하지 않는다)")
    void deployScriptWritesOnlyNamesComposeUses() {
        // 쓰는 방식이 둘이다 — ${NAME…} 치환, 그리고 값 없는 키(`NAME:`)가 .env 에 있을 때만 컨테이너에 넘어가는 형태
        Set<String> composeUses = new TreeSet<>();
        Matcher compose = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)|(?m)^ {6}([A-Z][A-Z0-9_]*):").matcher(prodCompose);
        while (compose.find()) {
            composeUses.add(compose.group(1) != null ? compose.group(1) : compose.group(2));
        }
        Set<String> written = new TreeSet<>();
        Matcher write = Pattern.compile("(?m)^\\s*write_env(?:_if_set)?\\s+([A-Z][A-Z0-9_]*)").matcher(deployScript);
        while (write.find()) {
            written.add(write.group(1));
        }

        assertThat(written).as("write_env 줄을 하나도 못 읽었다 — 정규식이 스크립트와 어긋났다").isNotEmpty();
        written.removeAll(composeUses);
        assertThat(written).as("deploy.sh 가 .env 에 쓰는데 운영 compose 가 쓰지 않는 이름").isEmpty();
    }

    @Test
    @DisplayName("R46 ops C #3 — 운영 compose 는 SPRING_PROFILES_ACTIVE 에 기본값을 두지 않는다(demo 로 조용히 뜨지 않는다)")
    void prodComposeHasNoDefaultProfile() {
        // 기본값이 demo 면 SSM 값이 빠진 배포가 가짜 시드 + 가짜 버스(DemoRunSimulator)로 뜬다.
        assertThat(composeServiceBlock("backend"))
                .contains("SPRING_PROFILES_ACTIVE: ${SPRING_PROFILES_ACTIVE:?")
                .doesNotContain("${SPRING_PROFILES_ACTIVE:-");
    }

    @Test
    @DisplayName("R46 ops C #5 — 학생 사진 저장 경로는 named volume 마운트 경로와 같다")
    void photoStorageRootIsOnANamedVolume() {
        // 마운트 경로와 저장 경로가 어긋나면 사진은 컨테이너 안에만 쌓여 재배포마다 사라진다 — 앱은 정상 기동한다.
        String backend = composeServiceBlock("backend");
        Matcher root = Pattern.compile("(?m)^ {6}PHOTO_STORAGE_ROOT: (\\S+)$").matcher(backend);
        assertThat(root.find()).as("compose 는 PHOTO_STORAGE_ROOT 를 고정값으로 넘긴다(SSM 으로 바꾸면 볼륨과 어긋난다)").isTrue();
        assertThat(backend).contains("- photo-data:" + root.group(1));
        assertThat(prodCompose).contains("\nvolumes:\n").containsPattern("(?m)^ {2}photo-data:$");
    }

    @Test
    @DisplayName("R46 ops C #11 — 운영 프록시의 /healthz 는 actuator health 만 프록시하고 /actuator 는 계속 404 다")
    void proxyExposesOnlyHealthzNotActuator() throws IOException {
        String nginx = Files.readString(Path.of("../infra/proxy/nginx.prod.conf"));
        int healthz = nginx.indexOf("location = /healthz");
        assertThat(healthz).as("외부 가동 감시가 칠 공개 헬스 주소").isNotNegative();
        String block = nginx.substring(healthz, nginx.indexOf('}', healthz));
        // health 상세는 show-details: never 라 {"status":"UP"} 뿐이다 — 정적 200 이면 backend 가 죽어도 감시가 못 안다.
        assertThat(block).contains("proxy_pass http://backend_pool/actuator/health");
        int actuator = nginx.indexOf("location /actuator");
        assertThat(nginx.substring(actuator, nginx.indexOf('}', actuator))).contains("return 404");
    }

    @Test
    @DisplayName("R46 ops2 Ruling 502 — 관계자 웹은 Vercel 이라 운영 compose 에 웹 서비스가 없고 프록시는 백엔드만 프록시하며 / 는 404 다")
    void prodServesNoWebFrontend() throws IOException {
        // 웹을 같은 EC2 에 다시 올리면 메모리(t3.medium 4GB)를 더 쓰고, 배포 경로가 둘이 되며, 웹·API 가 같은 출처가 되어
        // 지금의 출처·쿠키 설정(웹 = 다른 출처의 같은 사이트, DEPLOYMENT.md §12)이 어긋난다.
        String services = prodCompose.substring(0, prodCompose.indexOf("\nvolumes:\n"));
        Matcher names = Pattern.compile("(?m)^ {2}([a-z][a-z0-9-]*):\\s*$").matcher(services);
        Set<String> serviceNames = new TreeSet<>();
        while (names.find()) {
            serviceNames.add(names.group(1));
        }
        assertThat(serviceNames).as("운영 compose 서비스").contains("backend", "proxy")
                .noneMatch(name -> name.contains("web") || name.contains("frontend") || name.contains("academy"));

        String nginx = Files.readString(Path.of("../infra/proxy/nginx.prod.conf"));
        String directives = nginx.lines().filter(line -> !line.strip().startsWith("#")).collect(java.util.stream.Collectors.joining("\n"));
        Matcher proxyPass = Pattern.compile("proxy_pass\\s+(\\S+?);").matcher(directives);
        while (proxyPass.find()) {
            assertThat(proxyPass.group(1)).as("프록시 대상은 백엔드뿐이다").startsWith("http://backend_pool");
        }
        // 80 번 서버 블록에도 `location / {`(HTTPS 리다이렉트)가 있다 — 443 서버의 나머지 경로는 파일의 마지막 것이다.
        int root = directives.lastIndexOf("location / {");
        assertThat(root).as("나머지 경로 location").isNotNegative();
        assertThat(directives.substring(root, directives.indexOf('}', root))).contains("return 404");
    }

    @Test
    @DisplayName("R46 ops2 Ruling 500 — 백업 성공 시각 지표 폴더가 스크립트·부트스트랩·컴포즈 마운트·node-exporter 옵션에서 같다")
    void backupMetricFolderIsTheSameEverywhere() throws IOException {
        // 어긋나면 백업은 멀쩡한데 경보가 상시 울리거나(지표 부재), 백업이 멈춰도 못 보는 쪽으로 틀어진다.
        String folder = "/var/lib/node_exporter/textfile";
        String nodeExporter = composeServiceBlock("node-exporter");
        assertThat(nodeExporter).contains("--collector.textfile.directory=/textfile").contains("- " + folder + ":/textfile:ro");
        assertThat(Files.readString(Path.of("../infra/scripts/backup-db.sh"))).contains("TEXTFILE_DIR=\"${TEXTFILE_DIR:-" + folder + "}\"");
        assertThat(Files.readString(Path.of("../infra/scripts/bootstrap-ec2.sh"))).contains("TEXTFILE_DIR=" + folder);
    }

    @Test
    @DisplayName("demo 프로파일의 시드 비밀번호 해시에 기본값이 없다")
    void demoSeedHashHasNoDefault() {
        // 기본값(${SEED_PASSWORD_HASH:...})을 두면 주입을 깜빡해도 앱이 뜬다.
        // 그 결과 공개 도메인에 비밀번호 "password" 인 PLATFORM_ADMIN 이 열린다.
        assertThat(applicationYml)
                .as("demo 프로파일은 SEED_PASSWORD_HASH 를 기본값 없이 요구해야 한다")
                .contains("seedPasswordHash: ${SEED_PASSWORD_HASH}")
                .doesNotContain("seedPasswordHash: ${SEED_PASSWORD_HASH:");
    }

    @Test
    @DisplayName("옛 도메인 설정 블록(app.location·app.sos·app.connection·app.drivesession)이 부재한다")
    void legacyDomainConfigBlocksAreAbsent() {
        // 새 사양(바래다 재구축)에 대응물이 없는 옛 도메인 이름이라 폐기됐다(docs/archive/rounds/be-phases-0-14.md §1.2).
        // "locations:"(Flyway) 처럼 부분 문자열로 오탐하지 않도록 들여쓰기까지 포함한 키를 본다.
        assertThat(applicationYml)
                .as("옛 도메인 설정 블록은 공통·prod·demo 어느 섹션에도 없어야 한다")
                .doesNotContain("  location:\n")
                .doesNotContain("  sos:\n")
                .doesNotContain("  connection:\n")
                .doesNotContain("  drivesession:\n");
    }

    @Test
    @DisplayName("prod·demo 프로파일의 WebSocket 허용 출처에 와일드카드 기본값이 없다")
    void deployProfilesRequireExplicitWsOrigins() {
        // 공통 섹션의 기본값은 `${WS_ALLOWED_ORIGIN_PATTERNS:*}` 다 — 배포 프로파일이 이 키를
        // 다시 적지 않으면 그 `*` 를 조용히 상속해 **모든 출처에서 WebSocket 이 허용된다.**
        // REST 와 달리 STOMP 핸드셰이크는 CORS 필터를 타지 않아 이 값이 유일한 방어선이고,
        // 뚫려도 앱은 정상 기동하므로 배포 후에도 드러나지 않는다.
        for (String profile : new String[] {"prod", "demo"}) {
            assertThat(sectionOf("on-profile: " + profile))
                    .as("%s 프로파일은 WS_ALLOWED_ORIGIN_PATTERNS 를 기본값 없이 요구해야 한다"
                            + " (빠뜨리면 공통 섹션의 와일드카드 `*` 를 상속한다)", profile)
                    .contains("    allowed-origin-patterns: ${WS_ALLOWED_ORIGIN_PATTERNS}")
                    .doesNotContain("allowed-origin-patterns: ${WS_ALLOWED_ORIGIN_PATTERNS:");
        }
    }

    @Test
    @DisplayName("demo 프로파일 설명 주석이 삭제된 Mock 위치 소스 설정을 더 이상 언급하지 않는다")
    void demoCommentDoesNotReferenceRemovedMockConfig() {
        // 설정을 지우면서 그 근거를 설명하던 주석을 남겨두면, 없는 설정을 설명하는
        // 거짓 주석이 된다(docs/archive/rounds/be-phases-0-14.md §1.2 · Task 브리프 §2 주의사항).
        String demoSection = sectionOf("on-profile: demo");
        assertThat(demoSection)
                .as("demo 섹션은 더 이상 Mock 위치 소스 활성 근거를 설명하지 않아야 한다")
                .doesNotContain("Mock 위치 소스");
    }

    @Test
    @DisplayName("jwt 서명키에 공통 기본값이 없다")
    void jwtSecretHasNoDefaultInCommonSection() {
        // 첫 프로파일 구분자(---) 이전 = 공통 섹션. 공통 섹션에 기본값을 두면 prod·demo 가
        // JWT_SECRET 을 빠뜨려도 저장소에 공개된 키로 조용히 기동하고, 그 키로 아무 역할의
        // 토큰이나 위조할 수 있다(ws.allowed-origin-patterns 와 같은 방침).
        String common = applicationYml.substring(0, applicationYml.indexOf("\n---"));

        assertThat(common)
                .as("공통 섹션에 기본값을 두면 prod 에서 JWT_SECRET 미주입 시 저장소에 공개된 키로 조용히 기동한다")
                .contains("${JWT_SECRET}")
                .doesNotContain("${JWT_SECRET:");
    }

    @Test
    @DisplayName("local 프로파일은 jwt 서명키에 개발용 기본값을 둔다")
    void localProfileHasJwtSecretDefault() {
        // 공통 섹션에서 기본값을 뺀 대신, local 프로파일이 개발 편의를 위해 기본값을 되살린다.
        // 이 값이 실제로 다른 프로파일로 새지 않는지는 위 jwtSecretHasNoDefaultInCommonSection() 가 막는다.
        String localSection = sectionOf("on-profile: local");
        assertThat(localSection)
                .as("local 프로파일은 JWT_SECRET 미주입 시에도 개발용 기본값으로 기동해야 한다")
                .contains("${JWT_SECRET:local-dev-secret-change-me-please-32bytes-minimum-length}");
    }

    @Test
    @DisplayName("겹④ — 공통·prod·demo 섹션은 clean-disabled 를 true 로 명시하고 local 만 false 다")
    void flywayCleanDisabledIsExplicitPerProfile() {
        // Flyway 10+ 기본값도 true 이나, "겹①~③ 이 전부 뚫려도 라이브러리가 거부한다"는 마지막
        // 방어선을 기본값에 암묵적으로 맡기지 않고 파일에 명시로 고정한다(docs/IMPLEMENTATION_PLAN.md
        // §3.2 겹4). true 존재만 보면 같은 섹션에 반대값이 중복 키로 잘못 복사돼도(YAML 은 뒤 값이
        // 이겨 최후 방어선이 조용히 무력화돼도) 못 잡으므로 반대값의 부재까지 함께 건다.
        String common = applicationYml.substring(0, applicationYml.indexOf("\n---"));
        assertThat(common)
                .as("공통 섹션은 clean-disabled: true 를 명시하고 false 중복 키가 없어야 한다")
                .contains("clean-disabled: true")
                .doesNotContain("clean-disabled: false");

        for (String profile : new String[] {"prod", "demo"}) {
            assertThat(sectionOf("on-profile: " + profile))
                    .as("%s 프로파일 섹션은 clean-disabled: true 를 명시하고 false 중복 키가 없어야 한다", profile)
                    .contains("clean-disabled: true")
                    .doesNotContain("clean-disabled: false");
        }

        assertThat(sectionOf("on-profile: local"))
                .as("local 프로파일만 clean-disabled: false 로 열고 true 중복 키가 없어야 한다")
                .contains("clean-disabled: false")
                .doesNotContain("clean-disabled: true");
    }

    @Test
    @DisplayName("BR-168 — prod·demo 프로파일이 부하 측정값(Hikari 20 · STOMP 송신 32)을 명시한다")
    void deployProfilesPinLoadTestedPoolSizes() {
        // 부하 측정(2026-09-09 §13.5)이 정한 값이 어느 프로파일에도 없으면 2 vCPU 운영 기계에서
        // 커넥션 10 · 송신 스레드 4 로 기동한다(W12-02) — 측정은 끝났는데 반영이 안 된 상태를 막는다.
        for (String profile : new String[] {"prod", "demo"}) {
            String section = sectionOf("on-profile: " + profile);
            assertThat(section)
                    .as("%s 프로파일은 Hikari 최대 풀 크기를 20 으로 명시해야 한다", profile)
                    .contains("maximum-pool-size: 20");
            assertThat(section)
                    .as("%s 프로파일은 STOMP 송신 실행기 코어 스레드 수를 32 로 명시해야 한다", profile)
                    .contains("core-pool-size: 32");
        }
    }

    @Test
    @DisplayName("R46 D #17 — prod·demo·staging 은 Hikari 연결 대기 상한을 3초로 명시하고 staging·load 는 누수 감지를 켠다")
    void hikariTimeoutsArePinnedPerProfile() throws IOException {
        // 기본 30초는 풀이 찬 순간 요청 스레드가 연결을 30초씩 기다리며 쌓인다 — 앱은 정상 기동해 배포 뒤에야 드러난다.
        for (String profile : new String[] {"prod", "demo", "staging"}) {
            assertThat(sectionOf("on-profile: " + profile))
                    .as("%s 프로파일은 connection-timeout 3000 을 명시해야 한다", profile)
                    .contains("connection-timeout: 3000");
        }
        assertThat(sectionOf("on-profile: staging")).contains("leak-detection-threshold: 5000");
        assertThat(Files.readString(Path.of("src/main/resources/application-load.yml")))
                .as("부하 프로파일은 연결 보유자 특정을 위해 누수 감지를 켠다")
                .contains("leak-detection-threshold: 5000");
    }

    @Test
    @DisplayName("staging 프로파일은 local 의 공개된 값(시드 비밀번호·JWT 키·허용 출처 2종)을 기본값 없이 덮는다")
    void stagingProfileOverridesPublicLocalValues() {
        // 스테이징은 `local,staging` 으로 켜서 local 의 데모 시드·버스 시뮬레이터를 쓰되 공개 주소에 뜬다.
        // local 값이 하나라도 새면 시드 "password" 로 PLATFORM_ADMIN 로그인, 공개 JWT 키로 토큰 위조가
        // 누구에게나 열린다. 전부 앱은 정상 기동하는 형태다.
        String staging = sectionOf("on-profile: staging");
        assertThat(staging)
                .as("staging 은 공개된 local 값을 전부 기본값 없는 환경변수로 덮어야 한다")
                .contains("seedPasswordHash: ${SEED_PASSWORD_HASH}")
                .contains("secret: ${JWT_SECRET}")
                .contains("allowed-origins: ${CORS_ALLOWED_ORIGINS}")
                .contains("allowed-origin-patterns: ${WS_ALLOWED_ORIGIN_PATTERNS}")
                .doesNotContain("${SEED_PASSWORD_HASH:")
                .doesNotContain("${JWT_SECRET:")
                .doesNotContain("${CORS_ALLOWED_ORIGINS:")
                .doesNotContain("${WS_ALLOWED_ORIGIN_PATTERNS:");
        // 한 파일의 프로파일 문서는 뒤에 있는 것이 이긴다 — local 보다 앞에 두면 위 값이 전부 local 에 진다.
        assertThat(applicationYml.indexOf("on-profile: staging"))
                .as("staging 섹션은 local 섹션보다 뒤에 있어야 덮어쓴다")
                .isGreaterThan(applicationYml.indexOf("on-profile: local"));
    }

    @Test
    @DisplayName("Ruling 364 — staging 의 테스트 데이터 초기화는 실제로 DB 를 비운다(clean 억제 부재 · postgres 호스트만 추가 허용)")
    void stagingResetActuallyCleans() {
        // clean() 을 억제하면 POST /dev/reset 이 migrate() 만 하고 200 을 돌려준다 — 팀원은 초기화됐다고 믿지만
        // 데이터는 그대로인, 조용히 성공하는 형태다. 추가 허용 호스트는 스테이징 compose 의 DB 컨테이너 이름 하나뿐이어야 하고
        // 다른 섹션에는 없어야 한다(prod·demo 는 LocalFlywayCleanStrategy 자체가 막지만 local 개발은 localhost 만 허용).
        String staging = sectionOf("on-profile: staging");
        assertThat(staging)
                .as("staging 은 clean 을 억제하지 않고 초기화를 끄지 않으며, postgres 호스트만 추가로 연다")
                .doesNotContain("suppressed: true")
                .doesNotContain("reset:\n      enabled: false")
                .contains("extra-allowed-hosts: postgres\n");
        for (String section : applicationYml.split("(?m)^---$")) {
            if (!section.contains("on-profile: staging")) {
                assertThat(section)
                        .as("추가 허용 호스트는 staging 섹션에만 있어야 한다")
                        .doesNotContain("extra-allowed-hosts");
            }
        }
    }

    /**
     * BR-035 — 로그인 무차별 대입 완화 경로가 컨트롤러의 실제 경로(API 접두사 포함)와 어긋나 조용히 무효였다.
     * nginx 설정 문자열이 접두사를 바꾸거나 옛 {@code /api/auth/} 경로로 돌아가면 제한이 한 번도 걸리지 않는데도
     * 앱은 정상 동작하므로, 설정을 문자열로 읽어 실제 경로가 그 정규식에 걸리는지 본다.
     */
    @Test
    @DisplayName("nginx 요청 수 제한 location 이 API 접두사를 포함한 실제 인증 경로에 걸리고 옛 경로가 부재한다")
    void nginxRateLimitPathMatchesRealAuthPaths() throws IOException {
        String nginx = Files.readString(Path.of("../infra/proxy/nginx.prod.conf"));
        String regex = nginx.lines().map(String::strip)
                .filter(line -> line.startsWith("location ~ ") && line.endsWith("{"))
                .map(line -> line.substring("location ~ ".length(), line.length() - 1).strip())
                .filter(location -> location.contains("login"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("로그인 요청 수 제한 location(정규식)을 찾지 못했다"));

        // 주석은 옛 경로를 이력으로 언급하므로 설정 줄만 본다.
        String directives = nginx.lines().filter(line -> !line.strip().startsWith("#"))
                .collect(java.util.stream.Collectors.joining("\n"));
        assertThat(directives).as("존재하지 않는 옛 경로 — 제한이 한 번도 걸리지 않는다").doesNotContain("/api/auth/");
        for (String path : new String[] {"/auth/login", "/auth/signup", "/auth/signup/reapply", "/auth/recover",
                "/me/students/link"}) {
            assertThat((ApiPathPrefixConfig.API_PREFIX + path).matches(regex))
                    .as("%s 가 요청 수 제한 정규식 %s 에 걸리지 않는다", ApiPathPrefixConfig.API_PREFIX + path, regex)
                    .isTrue();
        }
        assertThat("/api/v1/auth/refresh".matches(regex)).as("refresh 는 추측 대상이 아니라 제한에서 뺀다").isFalse();
    }

    /** 운영 compose 의 서비스 하나(다음 서비스 머리나 최상위 키 직전까지)를 돌려준다. */
    private String composeServiceBlock(String service) {
        Matcher block = Pattern.compile("(?ms)^ {2}" + service + ":\\n(.*?)(?=^ {2}[a-z][a-z-]*:\\n|^[a-z]+:|\\z)")
                .matcher(prodCompose);
        if (!block.find()) {
            throw new AssertionError("운영 compose 에서 서비스를 찾지 못했다: " + service);
        }
        return block.group(1);
    }

    /** `---` 로 구분된 프로파일 문서 중 표식(marker)을 포함한 것을 돌려준다. */
    private String sectionOf(String marker) {
        for (String section : applicationYml.split("(?m)^---$")) {
            if (section.contains(marker)) {
                return section;
            }
        }
        throw new AssertionError("프로파일 섹션을 찾지 못했다: " + marker);
    }
}
