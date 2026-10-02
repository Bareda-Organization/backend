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
 * {@code infra/scripts/deploy-gate.sh} 를 가짜 {@code docker} 로 실제 실행해 4갈래를 본다 — BR-306 (R10-07).
 *
 * <p>게이트는 "운행 중(moving) 회차가 있으면 배포를 막는" 스크립트인데, 세어야 할 DB 가 아예 없는 최초 배포 · 재해 복구까지 막으면
 * 배포 경로 자체가 닫힌다. 반대로 "세지 못했다" 를 전부 통과로 처리하면 운행 중인 버스를 두고 배포하게 된다. 그래서 갈래가 넷이다 —
 * 컨테이너 없음 · {@code run} 테이블 없음은 이유 한 줄과 함께 통과, 컨테이너는 있는데 질의가 실패하거나 moving 이 있으면 실패.
 *
 * <p>진짜 compose 파일({@code docker-compose.prod.yml})에서 서비스명·DB명·역할을 읽는 경로를 그대로 탄다. 컨테이너는 건드리지 않는다.
 */
class DeployGateGuardTest {

    private static final Path GATE_SH = Path.of("..", "infra", "scripts", "deploy-gate.sh").toAbsolutePath().normalize();

    private static final Path PROD_COMPOSE = Path.of("..", "docker-compose.prod.yml").toAbsolutePath().normalize();

    /** 가짜 docker 의 상태 — 실제 운영 서버가 놓일 수 있는 모습을 환경변수로 고른다. */
    private static Map<String, String> state(String running, String runTable, String moving) {
        Map<String, String> state = new HashMap<>();
        state.put("FAKE_PG_RUNNING", running);
        state.put("FAKE_RUN_TABLE", runTable);
        state.put("FAKE_MOVING", moving);
        return state;
    }

    private static Map<String, String> withFailure(String failure) {
        Map<String, String> state = state("1", "1", "0");
        state.put("FAKE_FAIL", failure);
        return state;
    }

    static Stream<Arguments> unknownCountStates() {
        return Stream.of(
                Arguments.of("컨테이너는 있는데 run 테이블 존재 질의가 실패한다", withFailure("table")),
                Arguments.of("컨테이너는 있는데 moving 개수 질의가 실패한다", withFailure("count")),
                Arguments.of("docker 가 응답하지 않아 컨테이너가 있는지조차 모른다", withFailure("ps")));
    }

    @Test
    @DisplayName("postgres 컨테이너가 없으면(최초 배포 · 재해 복구) 통과하고 이유를 한 줄 남긴다")
    void passesWithAReasonWhenNoPostgresContainerIsRunning(@TempDir Path tmp) throws Exception {
        Run run = runGate(tmp, state("0", "0", "0"));

        assertThat(run.exitCode()).as("종료 코드 — 출력:\n%s", run.output()).isZero();
        assertThat(run.output()).contains("컨테이너");
        assertThat(run.output().strip().lines().count()).as("통과 이유는 한 줄 — 출력:\n%s", run.output()).isEqualTo(1);
    }

    @Test
    @DisplayName("컨테이너는 있는데 run 테이블이 아직 없으면(Flyway 전) 통과하고 이유를 한 줄 남긴다")
    void passesWithAReasonWhenRunTableDoesNotExistYet(@TempDir Path tmp) throws Exception {
        Run run = runGate(tmp, state("1", "0", "0"));

        assertThat(run.exitCode()).as("종료 코드 — 출력:\n%s", run.output()).isZero();
        assertThat(run.output()).contains("run 테이블");
        assertThat(run.output().strip().lines().count()).as("통과 이유는 한 줄 — 출력:\n%s", run.output()).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unknownCountStates")
    @DisplayName("세어야 할 DB 가 있는데 세지 못하면 통과시키지 않고 실패한다")
    void failsWhenTheCountCannotBeTakenFromARunningDatabase(String caseName, Map<String, String> state, @TempDir Path tmp)
            throws Exception {
        Run run = runGate(tmp, state);

        assertThat(run.exitCode()).as("종료 코드 — 출력:\n%s", run.output()).isNotZero();
        assertThat(run.output()).doesNotContain("배포 게이트 통과");
    }

    @Test
    @DisplayName("운행 중(moving) 회차가 단 1건이라도 있으면 건수와 함께 배포를 막는다")
    void failsWhenASingleRunIsMoving(@TempDir Path tmp) throws Exception {
        Run run = runGate(tmp, state("1", "1", "1"));

        assertThat(run.exitCode()).as("종료 코드 — 출력:\n%s", run.output()).isNotZero();
        assertThat(run.output()).contains("배포 중단").contains("1건");
    }

    @Test
    @DisplayName("run 테이블이 있고 moving 이 0건이면 통과한다")
    void passesWhenNothingIsMoving(@TempDir Path tmp) throws Exception {
        Run run = runGate(tmp, state("1", "1", "0"));

        assertThat(run.exitCode()).as("종료 코드 — 출력:\n%s", run.output()).isZero();
        assertThat(run.output()).contains("배포 게이트 통과").contains("moving");
    }

    // ── 실행 도구 ──

    private Run runGate(Path tmp, Map<String, String> fakeDockerState) throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        writeExecutable(bin.resolve("docker"), FAKE_DOCKER);

        ProcessBuilder builder = new ProcessBuilder(
                List.of("bash", GATE_SH.toString(), PROD_COMPOSE.toString(), tmp.resolve(".env").toString()));
        builder.redirectErrorStream(true);
        builder.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        builder.environment().remove("GATE_PG_URL");
        builder.environment().putAll(fakeDockerState);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("deploy-gate.sh 가 60초 안에 끝나지 않았다:\n" + output);
        }
        return new Run(process.exitValue(), output);
    }

    private static void writeExecutable(Path file, String content) throws IOException {
        Files.writeString(file, content);
        file.toFile().setExecutable(true);
    }

    private record Run(int exitCode, String output) {
    }

    /**
     * 진짜 docker compose 가 놓이는 모습을 흉내 낸다 — 컨테이너가 없으면 {@code ps} 는 빈 출력, {@code exec} 는 "not running" 으로 실패하고,
     * 테이블이 없으면 개수 질의는 "relation does not exist" 로 실패한다. 질의 내용은 마지막 인자({@code -tAc} 의 값)로 가른다.
     * {@code FAKE_FAIL} 은 {@code ps}·{@code table}·{@code count} 중 하나를 서버 쪽 오류로 실패시킨다.
     */
    private static final String FAKE_DOCKER = """
            #!/usr/bin/env bash
            query="${@: -1}"
            case " $* " in
              *" ps "*)
                if [ "$FAKE_FAIL" = ps ]; then echo "Cannot connect to the Docker daemon" >&2; exit 1; fi
                if [ "$FAKE_PG_RUNNING" = 1 ]; then echo "0123456789ab"; fi
                exit 0 ;;
              *" exec "*)
                if [ "$FAKE_PG_RUNNING" != 1 ]; then echo 'service "postgres" is not running' >&2; exit 1; fi
                case "$query" in
                  *to_regclass*)
                    if [ "$FAKE_FAIL" = table ]; then echo "psql: connection to server failed" >&2; exit 2; fi
                    if [ "$FAKE_RUN_TABLE" = 1 ]; then echo t; else echo f; fi
                    exit 0 ;;
                  *"count(*)"*)
                    if [ "$FAKE_FAIL" = count ]; then echo "psql: connection to server failed" >&2; exit 2; fi
                    if [ "$FAKE_RUN_TABLE" != 1 ]; then echo 'ERROR:  relation "run" does not exist' >&2; exit 2; fi
                    echo "$FAKE_MOVING"
                    exit 0 ;;
                esac ;;
            esac
            exit 0
            """;
}
