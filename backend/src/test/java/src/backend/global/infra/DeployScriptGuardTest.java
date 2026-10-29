package src.backend.global.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code infra/scripts/deploy.sh} 를 가짜 {@code aws} · {@code docker} 로 실제 실행해 본다.
 *
 * <p>스크립트 글자만 읽어서는 "값이 비면 스크립트가 멈춘다" 도 "새 이미지가 안 뜨면 직전 태그로 되돌린다" 도 볼 수 없다. 여기서 막는
 * 사고는 전부 조용히 성공하는 종류다 — 프로파일 값이 빠진 배포가 가짜 시드로 뜨고, Directions 경로만 바꾼 배포가
 * 문서 밖 동작(Ruling 361)에 기대고, 해시가 아닌 값의 첫 관리자가 로그인 불가로 뜨고, 안 뜨는 이미지가 배포된 채 남는다.
 *
 * <p>1단계(SSM → {@code .env})만 보는 시험은 가짜 {@code docker} 없이 돈다 — 1단계에서 실패하면 Docker 에 닿기 전에 끝나고, 통과해도 가짜
 * {@code APP_DIR} 에 compose 파일이 없어 다음 단계에서 멈추므로 실제 컨테이너는 건드리지 않는다. 롤백 시험은 가짜 {@code docker} 가 호출 인자와
 * 그 순간의 {@code .env} 태그를 기록하고, 지정한 태그에서만 실패하거나 헬스체크에 응하지 않는다.
 */
class DeployScriptGuardTest {

    private static final Path DEPLOY_SH = Path.of("..", "infra", "scripts", "deploy.sh").toAbsolutePath().normalize();

    private static final String BCRYPT_HASH = "$2y$10$" + "a".repeat(53);

    /** 필수 값 전부 — demo 프로파일 기준. */
    private static Map<String, String> requiredParams() {
        Map<String, String> params = new HashMap<>();
        params.put("ECR_REGISTRY", "111122223333.dkr.ecr.ap-northeast-2.amazonaws.com");
        params.put("DB_PASSWORD", "db-pass");
        params.put("JWT_SECRET", "jwt-secret-jwt-secret-jwt-secret-32b");
        params.put("SEED_PASSWORD_HASH", BCRYPT_HASH);
        params.put("CORS_ALLOWED_ORIGINS", "https://app.example.com");
        params.put("WS_ALLOWED_ORIGIN_PATTERNS", "https://app.example.com");
        params.put("NAVER_DIRECTIONS_KEY_ID", "key-id");
        params.put("NAVER_DIRECTIONS_KEY", "key");
        params.put("SPRING_PROFILES_ACTIVE", "demo");
        params.put("GRAFANA_ADMIN_PASSWORD", "grafana-pass");
        return params;
    }

    private static Map<String, String> with(String name, String value) {
        Map<String, String> params = requiredParams();
        params.put(name, value);
        return params;
    }

    private static Map<String, String> without(String name) {
        Map<String, String> params = requiredParams();
        params.remove(name);
        return params;
    }

    private static Map<String, String> prodWithFcm() {
        Map<String, String> params = with("SPRING_PROFILES_ACTIVE", "prod");
        params.put("FCM_PROJECT_ID", "proj");
        params.put("FCM_CLIENT_EMAIL", "sa@proj.iam.gserviceaccount.com");
        params.put("FCM_PRIVATE_KEY", "-----BEGIN PRIVATE KEY-----\\nabc\\n-----END PRIVATE KEY-----\\n");
        return params;
    }

    static Stream<Arguments> stoppedDeployments() {
        Map<String, String> pathOnly = with("NAVER_DIRECTIONS_PATH", "/map-direction/v1/driving");
        Map<String, String> pointsOnly = with("NAVER_DIRECTIONS_MAX_POINTS", "7");
        Map<String, String> loginOnly = with("BOOTSTRAP_ADMIN_LOGIN_ID", "owner");
        Map<String, String> plainHash = with("BOOTSTRAP_ADMIN_LOGIN_ID", "owner");
        plainHash.put("BOOTSTRAP_ADMIN_PASSWORD_HASH", "plain-password-1234");
        Map<String, String> prodNoFcm = with("SPRING_PROFILES_ACTIVE", "prod");
        return Stream.of(
                Arguments.of("프로파일 값이 SSM 에 없다", without("SPRING_PROFILES_ACTIVE"), "SPRING_PROFILES_ACTIVE"),
                Arguments.of("프로파일이 prod·demo 가 아니다", with("SPRING_PROFILES_ACTIVE", "dev"), "prod"),
                Arguments.of("prod 인데 FCM 자격 증명이 없다", prodNoFcm, "FCM_PROJECT_ID"),
                Arguments.of("Directions 경로만 있고 지점 수가 없다", pathOnly, "NAVER_DIRECTIONS_MAX_POINTS"),
                Arguments.of("Directions 지점 수만 있고 경로가 없다", pointsOnly, "NAVER_DIRECTIONS_PATH"),
                Arguments.of("첫 관리자 아이디만 있고 해시가 없다", loginOnly, "BOOTSTRAP_ADMIN_PASSWORD_HASH"),
                Arguments.of("첫 관리자 해시가 bcrypt 형식이 아니다", plainHash, "bcrypt"),
                Arguments.of("CORS 허용 출처에 와일드카드가 있다", with("CORS_ALLOWED_ORIGINS", "https://*.example.com"),
                        "CORS_ALLOWED_ORIGINS"),
                Arguments.of("WebSocket 허용 출처가 https 가 아니다", with("WS_ALLOWED_ORIGIN_PATTERNS", "http://app.example.com"),
                        "WS_ALLOWED_ORIGIN_PATTERNS"),
                Arguments.of("허용 출처에 Vercel 기본 도메인이 있다(다른 사이트라 쿠키가 안 붙고 미리보기가 섞인다)",
                        with("CORS_ALLOWED_ORIGINS", "https://app.example.com,https://school-bus.vercel.app"), "vercel.app"),
                Arguments.of("경보 텔레그램 토큰만 있고 채팅 ID 가 없다", with("ALERT_TELEGRAM_BOT_TOKEN", "123456:AAE-token"),
                        "ALERT_TELEGRAM_CHAT_ID"),
                Arguments.of("경보 이메일 수신 주소만 있고 SMTP 값이 없다", with("ALERT_EMAIL_TO", "ops@example.com"), "ALERT_SMTP_HOST"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stoppedDeployments")
    @DisplayName("SSM 값이 잘못됐으면 deploy.sh 는 컨테이너에 닿기 전에 멈추고 .env 를 만들지 않는다")
    void deploymentStopsBeforeTouchingAnything(String caseName, Map<String, String> params, String expectedMessage,
            @TempDir Path tmp) throws Exception {
        Run run = runDeploy(tmp, params);

        assertThat(run.exitCode()).as("종료 코드 — 출력:\n%s", run.output()).isNotZero();
        assertThat(run.output()).contains(expectedMessage);
        assertThat(run.envFile()).as("멈춘 배포는 기존 .env 를 건드리지 않는다").isNull();
        assertThat(run.output()).as("비밀값을 출력에 싣지 않는다").doesNotContain("plain-password-1234");
    }

    @Test
    @DisplayName("선택 항목이 없으면 .env 에 쓰지 않는다 — 컨테이너가 yml 기본값으로 뜬다")
    void optionalParamsAreOmittedWhenAbsent(@TempDir Path tmp) throws Exception {
        Run run = runDeploy(tmp, requiredParams());

        assertThat(run.envFile()).contains("SPRING_PROFILES_ACTIVE='demo'").contains("GRAFANA_ADMIN_PASSWORD='grafana-pass'")
                .doesNotContain("NAVER_DIRECTIONS_PATH").doesNotContain("NAVER_DIRECTIONS_MAX_POINTS")
                .doesNotContain("NAVER_SEARCH_CLIENT_ID").doesNotContain("BOOTSTRAP_ADMIN_LOGIN_ID")
                .doesNotContain("FCM_");
    }

    @Test
    @DisplayName("선택 항목이 있으면 .env 에 그대로 쓴다 — Directions 두 값은 짝으로")
    void optionalParamsAreWrittenWhenPresent(@TempDir Path tmp) throws Exception {
        Map<String, String> params = prodWithFcm();
        params.put("NAVER_DIRECTIONS_PATH", "/map-direction/v1/driving");
        params.put("NAVER_DIRECTIONS_MAX_POINTS", "7");
        params.put("NAVER_SEARCH_CLIENT_ID", "search-id");
        params.put("NAVER_SEARCH_CLIENT_SECRET", "search-secret");
        params.put("BOOTSTRAP_ADMIN_LOGIN_ID", "owner");
        params.put("BOOTSTRAP_ADMIN_PASSWORD_HASH", BCRYPT_HASH);

        Run run = runDeploy(tmp, params);

        assertThat(run.envFile()).contains("SPRING_PROFILES_ACTIVE='prod'")
                .contains("NAVER_DIRECTIONS_PATH='/map-direction/v1/driving'").contains("NAVER_DIRECTIONS_MAX_POINTS='7'")
                .contains("NAVER_SEARCH_CLIENT_ID='search-id'").contains("NAVER_SEARCH_CLIENT_SECRET='search-secret'")
                .contains("BOOTSTRAP_ADMIN_LOGIN_ID='owner'").contains("BOOTSTRAP_ADMIN_PASSWORD_HASH='" + BCRYPT_HASH + "'")
                .contains("FCM_PROJECT_ID='proj'");
    }

    @Test
    @DisplayName(".env 는 임시 파일에 다 쓴 뒤 이름을 바꿔 교체한다 — 같은 파일을 비우고 다시 쓰면 그 사이 읽는 호출(백업 크론)이 필수 변수 없는 빈 파일을 본다(BR-372)")
    void envFileIsReplacedAtomically(@TempDir Path tmp) throws Exception {
        Path env = Files.createDirectories(tmp.resolve("app")).resolve(".env");
        Files.writeString(env, "OLD_VALUE='1'\n");
        Object before = Files.readAttributes(env, BasicFileAttributes.class).fileKey();

        Run run = runDeploy(tmp, requiredParams());

        assertThat(run.envFile()).contains("SPRING_PROFILES_ACTIVE='demo'").doesNotContain("OLD_VALUE");
        assertThat(Files.readAttributes(env, BasicFileAttributes.class).fileKey())
                .as("같은 inode 면 기존 파일을 비우고 다시 쓴 것이다 — 새 파일을 만들어 이름으로 덮어야 읽는 쪽이 옛 파일 아니면 새 파일만 본다").isNotEqualTo(before);
        assertThat(Files.list(env.getParent()).map(path -> path.getFileName().toString()))
                .as("임시 파일이 남지 않는다").doesNotContain(".env.tmp");
    }

    @Test
    @DisplayName("경보 수신 값이 있으면 Alertmanager 설정 파일에만 쓰고 .env 에는 싣지 않는다 — 없으면 수신자 없는 파일을 만든다")
    void alertReceiversAreRenderedIntoTheAlertmanagerFileOnly(@TempDir Path tmp, @TempDir Path tmpNone) throws Exception {
        Map<String, String> params = requiredParams();
        params.put("ALERT_TELEGRAM_BOT_TOKEN", "123456:AAE-token");
        params.put("ALERT_TELEGRAM_CHAT_ID", "-100123");

        Run with = runDeploy(tmp, params);
        Run none = runDeploy(tmpNone, requiredParams());

        assertThat(with.alertmanagerYml()).contains("telegram_configs").contains("chat_id: -100123");
        assertThat(with.envFile()).doesNotContain("ALERT_").doesNotContain("AAE-token");
        assertThat(with.output()).as("비밀값을 출력에 싣지 않는다").doesNotContain("AAE-token");
        // 파일이 항상 있어야 compose 의 바인드 마운트가 폴더를 새로 만들어 버리지 않는다.
        assertThat(none.alertmanagerYml()).contains("receiver: notify").doesNotContain("telegram_configs").doesNotContain("email_configs");
    }

    @Test
    @DisplayName("SSM 조회가 ParameterNotFound 가 아닌 오류로 실패하면 선택 항목도 없는 값으로 넘기지 않고 멈춘다")
    void accessDeniedOnAnOptionalParamStopsTheDeployment(@TempDir Path tmp) throws Exception {
        Run run = runDeploy(tmp, requiredParams(), Map.of("FAKE_SSM_DENY", "NAVER_DIRECTIONS_PATH"));

        assertThat(run.exitCode()).isNotZero();
        assertThat(run.output()).contains("NAVER_DIRECTIONS_PATH");
        assertThat(run.envFile()).isNull();
    }

    @Test
    @DisplayName("성공한 배포는 안 쓰는 이미지를 72시간 뒤 전부 지우고 CloudWatch 로그 보관 기간을 건다")
    void deploySuccessTailCleansImagesAndSetsLogRetention() throws IOException {
        String script = Files.readString(DEPLOY_SH);

        // 옛 SHA 태그 이미지는 이름이 있어 `prune -f` 로는 안 지워진다 — 매 배포마다 이미지 ~400MB 가 쌓인다.
        assertThat(script).contains("docker image prune -af --filter until=72h").doesNotContain("docker image prune -f ");
        assertThat(script).contains("aws logs put-retention-policy").contains("LOG_RETENTION_DAYS=7");
    }

    // ── 롤백 ──

    @Test
    @DisplayName("새 이미지가 스모크 테스트를 통과하지 못하면 직전 태그로 다시 띄우고 .env 도 직전 태그로 되돌리되 배포는 실패로 끝낸다")
    void failedSmokeTestRedeploysThePreviousTag(@TempDir Path tmp) throws Exception {
        Run run = runFullDeploy(tmp, "oldtag", Map.of("FAKE_BAD_TAG", NEW_TAG));

        assertThat(run.exitCode()).as("되돌려도 새 태그 배포는 실패다 — 출력:\n%s", run.output()).isNotZero();
        assertThat(run.dockerTagsOf("up -d")).as("기동 호출이 본 .env 태그: 새 태그 → 직전 태그").containsExactly(NEW_TAG, "oldtag");
        assertThat(run.dockerTagsOf("pull backend")).as("직전 이미지는 이미 지워졌을 수 있어 다시 받는다").containsExactly(NEW_TAG, "oldtag");
        assertThat(run.envFile()).contains("IMAGE_TAG='oldtag'").doesNotContain(NEW_TAG);
        assertThat(run.output()).contains("oldtag");
    }

    @Test
    @DisplayName("로그를 읽어 오지 못해도 스모크 테스트 실패 뒤의 되돌리기는 건너뛰지 않는다")
    void rollbackRunsEvenWhenReadingLogsFails(@TempDir Path tmp) throws Exception {
        Run run = runFullDeploy(tmp, "oldtag", Map.of("FAKE_BAD_TAG", NEW_TAG, "FAKE_LOGS_FAIL", "1"));

        assertThat(run.exitCode()).isNotZero();
        assertThat(run.dockerTagsOf("up -d")).as("로그 조회가 실패해도 직전 태그 기동까지 간다").containsExactly(NEW_TAG, "oldtag");
        assertThat(run.envFile()).contains("IMAGE_TAG='oldtag'");
    }

    @Test
    @DisplayName("직전 태그로 되돌린 뒤에도 백엔드가 안 뜨면 그 사실을 알리고 실패로 끝낸다")
    void rollbackThatDoesNotComeUpIsReported(@TempDir Path tmp) throws Exception {
        Run run = runFullDeploy(tmp, "oldtag", Map.of("FAKE_BAD_TAG", NEW_TAG + ",oldtag"));

        assertThat(run.exitCode()).isNotZero();
        assertThat(run.output()).contains("되돌린 태그(oldtag)도");
    }

    @Test
    @DisplayName("스모크 테스트를 통과한 배포는 되돌리지 않는다 — 기동은 한 번이고 .env 는 새 태그다")
    void healthyDeployIsNotRolledBack(@TempDir Path tmp) throws Exception {
        Run run = runFullDeploy(tmp, "oldtag", Map.of());

        assertThat(run.exitCode()).as("출력:\n%s", run.output()).isZero();
        assertThat(run.dockerTagsOf("up -d")).containsExactly(NEW_TAG);
        assertThat(run.envFile()).contains("IMAGE_TAG='" + NEW_TAG + "'");
    }

    @Test
    @DisplayName("기동 단계(up -d)에서 새 이미지가 실패해도 직전 태그로 다시 띄운다")
    void failedStartRedeploysThePreviousTag(@TempDir Path tmp) throws Exception {
        Run run = runFullDeploy(tmp, "oldtag", Map.of("FAKE_UP_FAIL_TAG", NEW_TAG));

        assertThat(run.exitCode()).isNotZero();
        assertThat(run.dockerTagsOf("up -d")).containsExactly(NEW_TAG, "oldtag");
        assertThat(run.envFile()).contains("IMAGE_TAG='oldtag'");
    }

    @Test
    @DisplayName("이미지 수신이 실패하면 컨테이너를 건드리지 않고 .env 만 직전 태그로 되돌린다")
    void failedPullKeepsThePreviousTagInEnv(@TempDir Path tmp) throws Exception {
        Run run = runFullDeploy(tmp, "oldtag", Map.of("FAKE_PULL_FAIL_TAG", NEW_TAG));

        assertThat(run.exitCode()).isNotZero();
        assertThat(run.dockerTagsOf("up -d")).as("받지 못한 이미지로 컨테이너를 만들지 않는다").isEmpty();
        assertThat(run.envFile()).contains("IMAGE_TAG='oldtag'").doesNotContain(NEW_TAG);
    }

    @Test
    @DisplayName("직전 태그가 없는 첫 배포가 실패하면 되돌릴 곳이 없다고 알린다")
    void firstDeployFailureSaysThereIsNothingToRollBackTo(@TempDir Path tmp) throws Exception {
        Run run = runFullDeploy(tmp, null, Map.of("FAKE_BAD_TAG", NEW_TAG));

        assertThat(run.exitCode()).isNotZero();
        assertThat(run.dockerTagsOf("up -d")).containsExactly(NEW_TAG);
        assertThat(run.output()).contains("되돌릴 직전 이미지 태그가 없다");
    }

    @Test
    @DisplayName("같은 태그를 다시 배포하다 실패하면 자기 자신으로 되돌리지 않는다")
    void redeployOfTheSameTagIsNotRolledBackToItself(@TempDir Path tmp) throws Exception {
        Run run = runFullDeploy(tmp, NEW_TAG, Map.of("FAKE_BAD_TAG", NEW_TAG));

        assertThat(run.exitCode()).isNotZero();
        assertThat(run.dockerTagsOf("up -d")).containsExactly(NEW_TAG);
    }

    // ── 실행 도구 ──

    private static final String NEW_TAG = "0123456789abcdef";

    private Run runDeploy(Path tmp, Map<String, String> ssmParams) throws Exception {
        return runDeploy(tmp, ssmParams, Map.of());
    }

    /** 가짜 aws · docker · sleep 으로 deploy.sh 를 끝까지 돌린다. {@code previousTag} 가 있으면 직전 배포가 남긴 {@code .env} 를 먼저 둔다. */
    private Run runFullDeploy(Path tmp, String previousTag, Map<String, String> fakeDockerEnv) throws Exception {
        Path app = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(app.resolve("docker-compose.prod.yml"), """
                services:
                  postgres:
                    environment:
                      POSTGRES_DB: schoolbus
                      POSTGRES_USER: schoolbus
                """);
        if (previousTag != null) {
            Files.writeString(app.resolve(".env"), "IMAGE_TAG='" + previousTag + "'\n");
        }
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        writeExecutable(bin.resolve("docker"), FAKE_DOCKER);
        writeExecutable(bin.resolve("sleep"), "#!/bin/sh\nexit 0\n");
        Map<String, String> env = new HashMap<>(fakeDockerEnv);
        env.put("FAKE_DOCKER_LOG", tmp.resolve("docker.log").toString());
        env.put("FAKE_RENDERED_HASH", BCRYPT_HASH.replace("$", "$$"));
        return runDeploy(tmp, requiredParams(), env);
    }

    private Run runDeploy(Path tmp, Map<String, String> ssmParams, Map<String, String> extraEnv) throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path ssm = Files.createDirectories(tmp.resolve("ssm"));
        Path app = Files.createDirectories(tmp.resolve("app"));
        writeExecutable(bin.resolve("aws"), FAKE_AWS);
        for (Map.Entry<String, String> param : ssmParams.entrySet()) {
            Files.writeString(ssm.resolve(param.getKey()), param.getValue());
        }

        ProcessBuilder builder = new ProcessBuilder(List.of("bash", DEPLOY_SH.toString(), NEW_TAG));
        builder.redirectErrorStream(true);
        builder.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        builder.environment().put("APP_DIR", app.toString());
        builder.environment().put("FAKE_SSM_DIR", ssm.toString());
        builder.environment().putAll(extraEnv);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("deploy.sh 가 60초 안에 끝나지 않았다:\n" + output);
        }
        Path env = app.resolve(".env");
        Path alertmanager = app.resolve("alertmanager").resolve("alertmanager.yml");
        Path dockerLog = tmp.resolve("docker.log");
        return new Run(process.exitValue(), output, Files.exists(env) ? Files.readString(env) : null,
                Files.exists(alertmanager) ? Files.readString(alertmanager) : null,
                Files.exists(dockerLog) ? Files.readAllLines(dockerLog) : List.of());
    }

    private static void writeExecutable(Path file, String content) throws IOException {
        Files.writeString(file, content);
        file.toFile().setExecutable(true);
    }

    /**
     * 실행 결과 — {@code envFile}·{@code alertmanagerYml} 은 만들어지지 않았으면 {@code null}. {@code dockerCalls} 는 가짜 docker 가 받은
     * 호출마다 한 줄 {@code tag=<그 순간 .env 의 IMAGE_TAG> <인자…>}.
     */
    private record Run(int exitCode, String output, String envFile, String alertmanagerYml, List<String> dockerCalls) {

        /** 인자에 {@code command} 가 든 docker 호출들이 호출 순간 본 {@code .env} 태그를 호출 순서대로. */
        List<String> dockerTagsOf(String command) {
            return dockerCalls.stream().filter(call -> call.endsWith(" " + command))
                    .map(call -> call.substring("tag=".length(), call.indexOf(' '))).toList();
        }
    }

    /**
     * docker 호출을 기록하고({@code FAKE_DOCKER_LOG}) 환경변수가 가리키는 태그에서만 실패한다. 태그 목록은 쉼표로 잇는다 —
     * {@code FAKE_PULL_FAIL_TAG}(이미지 수신) · {@code FAKE_UP_FAIL_TAG}(기동) · {@code FAKE_BAD_TAG}(헬스체크 무응답), {@code FAKE_LOGS_FAIL}(로그 조회 실패, 값이 있으면 태그와 무관).
     * 그 밖의 호출은 성공이고, compose 설정 출력은 {@code FAKE_RENDERED_HASH} 를 시드 해시로 보여 준다.
     */
    private static final String FAKE_DOCKER = """
            #!/usr/bin/env bash
            tag="$(sed -n "s/^IMAGE_TAG='\\\\(.*\\\\)'$/\\\\1/p" "$APP_DIR/.env" 2>/dev/null | head -n 1)"
            echo "tag=$tag $*" >> "$FAKE_DOCKER_LOG"
            listed() { [ -n "$2" ] && case ",$1," in *",$2,"*) return 0;; esac; return 1; }
            case "$*" in
              login*) cat > /dev/null ;;
              *" config") printf '      SEED_PASSWORD_HASH: %s\\n' "$FAKE_RENDERED_HASH" ;;
              *" pull backend") listed "${FAKE_PULL_FAIL_TAG:-}" "$tag" && exit 1 ;;
              *" up -d") listed "${FAKE_UP_FAIL_TAG:-}" "$tag" && exit 1 ;;
              *" logs "*) [ -n "${FAKE_LOGS_FAIL:-}" ] && exit 1 ;;
              *" exec -T backend curl"*) listed "${FAKE_BAD_TAG:-}" "$tag" && exit 1; echo '{"status":"UP"}' ;;
            esac
            exit 0
            """;

    /**
     * SSM 조회는 {@code $FAKE_SSM_DIR/<이름>} 파일에서 읽고(없으면 진짜와 같은 ParameterNotFound), 그 밖의 호출은
     * 성공으로 넘긴다. {@code FAKE_SSM_DENY} 이름은 권한 오류로 실패시킨다.
     */
    private static final String FAKE_AWS = """
            #!/usr/bin/env bash
            if [ "$1 $2" = "ssm get-parameter" ]; then
              name=""
              while [ $# -gt 0 ]; do [ "$1" = "--name" ] && name="$(basename "$2")"; shift; done
              if [ "$name" = "$FAKE_SSM_DENY" ]; then
                echo "An error occurred (AccessDeniedException) when calling the GetParameter operation: denied" >&2
                exit 254
              fi
              if [ -f "$FAKE_SSM_DIR/$name" ]; then cat "$FAKE_SSM_DIR/$name"; exit 0; fi
              echo "An error occurred (ParameterNotFound) when calling the GetParameter operation: not found" >&2
              exit 254
            fi
            exit 0
            """;
}
