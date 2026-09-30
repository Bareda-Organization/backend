package src.backend.global.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * {@code infra/scripts/deploy.sh} 1단계(SSM → {@code .env})를 가짜 {@code aws} 로 실제 실행해 본다 — R46 ops C #3·#4.
 *
 * <p>텍스트만 읽는 {@code DeploymentConfigGuardTest} 로는 "값이 비면 스크립트가 멈춘다" 를 볼 수 없다. 여기서 막는
 * 사고는 전부 조용히 성공하는 종류다 — 프로파일 값이 빠진 배포가 가짜 시드로 뜨고, Directions 경로만 바꾼 배포가
 * 문서 밖 동작(Ruling 361)에 기대고, 해시가 아닌 값의 첫 관리자가 로그인 불가로 뜬다.
 *
 * <p>스크립트는 1단계에서 실패하면 Docker 에 닿기 전에 끝난다. 통과한 경우에도 가짜 {@code APP_DIR} 에 compose
 * 파일이 없어 다음 단계에서 멈추므로 실제 컨테이너는 건드리지 않는다 — {@code .env} 내용만 본다.
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
        params.put("ROUTING_PROVIDER", "naver");
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
                Arguments.of("첫 관리자 해시가 bcrypt 형식이 아니다", plainHash, "bcrypt"));
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

        // 옛 SHA 태그 이미지는 이름이 있어 `prune -f` 로는 안 지워진다 — 매 배포마다 이미지 ~400MB 가 쌓인다(C #18).
        assertThat(script).contains("docker image prune -af --filter until=72h").doesNotContain("docker image prune -f ");
        assertThat(script).contains("aws logs put-retention-policy").contains("LOG_RETENTION_DAYS=7");
    }

    // ── 실행 도구 ──

    private Run runDeploy(Path tmp, Map<String, String> ssmParams) throws Exception {
        return runDeploy(tmp, ssmParams, Map.of());
    }

    private Run runDeploy(Path tmp, Map<String, String> ssmParams, Map<String, String> extraEnv) throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path ssm = Files.createDirectories(tmp.resolve("ssm"));
        Path app = Files.createDirectories(tmp.resolve("app"));
        writeExecutable(bin.resolve("aws"), FAKE_AWS);
        for (Map.Entry<String, String> param : ssmParams.entrySet()) {
            Files.writeString(ssm.resolve(param.getKey()), param.getValue());
        }

        ProcessBuilder builder = new ProcessBuilder(List.of("bash", DEPLOY_SH.toString(), "0123456789abcdef"));
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
        return new Run(process.exitValue(), output, Files.exists(env) ? Files.readString(env) : null);
    }

    private static void writeExecutable(Path file, String content) throws IOException {
        Files.writeString(file, content);
        file.toFile().setExecutable(true);
    }

    /** 실행 결과 — {@code envFile} 은 만들어지지 않았으면 {@code null}. */
    private record Run(int exitCode, String output, String envFile) {
    }

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
