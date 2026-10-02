package src.backend.global.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code infra/scripts/backup-db.sh} 를 가짜 {@code docker}·{@code aws} 로 실제 실행해 본다 — R46 ops2 `Ruling 500`.
 *
 * <p>백업이 조용히 멈추는 것이 이 시험이 막는 사고다. 백업 성공 시각 지표(node-exporter textfile)는
 * <b>S3 에 올린 뒤에만</b> 갱신돼야 경보({@code BackupDbStale})가 "멈춤"을 본다 — 덤프만 뜨고 업로드가 실패했는데
 * 지표가 갱신되면 백업이 없는데 초록이다.
 */
class BackupScriptGuardTest {

    private static final Path BACKUP_SH = Path.of("..", "infra", "scripts", "backup-db.sh").toAbsolutePath().normalize();

    private static final Path BOOTSTRAP_SH = Path.of("..", "infra", "scripts", "bootstrap-ec2.sh").toAbsolutePath().normalize();

    /** 종류별로 이름이 다르다 — 같은 이름이면 node-exporter 가 두 파일의 도움말 문구 불일치로 둘 다 버린다(실측). */
    private static String metric(String kind) {
        return "schoolbus_backup_" + kind + "_last_success_timestamp_seconds";
    }

    @Test
    @DisplayName("db 백업은 덤프를 S3 db/ 에 올린 뒤에만 DB 성공 시각 지표를 쓰고 사진은 건드리지 않는다")
    void dbBackupWritesMetricOnlyAfterUpload(@TempDir Path tmp) throws Exception {
        Run run = run(tmp, List.of("db"), Map.of());

        assertThat(run.exitCode()).as("출력:\n%s", run.output()).isZero();
        assertThat(run.awsCalls()).anyMatch(call -> call.contains("s3://test-bucket/db/")).noneMatch(call -> call.contains("/photos/"));
        assertThat(run.metric("db")).contains(metric("db") + " ");
        assertThat(run.metric("photos")).isNull();
        long written = Long.parseLong(run.metric("db").lines().filter(line -> line.startsWith(metric("db"))).findFirst().orElseThrow()
                .split(" ")[1]);
        assertThat(written).as("지표 값은 지금 시각(epoch 초)이다").isBetween(System.currentTimeMillis() / 1000 - 60,
                System.currentTimeMillis() / 1000 + 60);
    }

    @Test
    @DisplayName("photos 백업은 사진 묶음을 S3 photos/ 에 올린 뒤에만 사진 성공 시각 지표를 쓴다")
    void photosBackupWritesItsOwnMetric(@TempDir Path tmp) throws Exception {
        Run run = run(tmp, List.of("photos"), Map.of());

        assertThat(run.exitCode()).as("출력:\n%s", run.output()).isZero();
        assertThat(run.awsCalls()).anyMatch(call -> call.contains("s3://test-bucket/photos/")).noneMatch(call -> call.contains("/db/"));
        assertThat(run.metric("photos")).contains(metric("photos") + " ");
        assertThat(run.metric("db")).isNull();
    }

    @Test
    @DisplayName("인자가 없으면(손으로 첫 백업) 둘 다 올리고 지표도 둘 다 쓴다")
    void noArgumentBacksUpBoth(@TempDir Path tmp) throws Exception {
        Run run = run(tmp, List.of(), Map.of());

        assertThat(run.exitCode()).as("출력:\n%s", run.output()).isZero();
        assertThat(run.metric("db")).isNotNull();
        assertThat(run.metric("photos")).isNotNull();
    }

    @Test
    @DisplayName("두 지표 파일은 지표 이름이 서로 다르다 — 같은 이름이면 node-exporter 가 도움말 불일치로 둘 다 버려 경보가 거짓으로 울린다")
    void metricNamesDifferPerKind(@TempDir Path tmp) throws Exception {
        Run run = run(tmp, List.of(), Map.of());

        String db = run.metric("db").lines().filter(line -> !line.startsWith("#")).findFirst().orElseThrow().split("[ {]")[0];
        String photos = run.metric("photos").lines().filter(line -> !line.startsWith("#")).findFirst().orElseThrow().split("[ {]")[0];
        assertThat(db).isNotEqualTo(photos);
    }

    @Test
    @DisplayName("S3 업로드가 실패하면 비영 종료하고 지표를 갱신하지 않는다 — 옛 지표가 그대로 남아 경보가 멈춤을 본다")
    void failedUploadLeavesTheMetricUntouched(@TempDir Path tmp) throws Exception {
        Run run = run(tmp, List.of("db"), Map.of("FAKE_AWS_FAIL", "1"), "# 옛 지표\n");

        assertThat(run.exitCode()).isNotZero();
        assertThat(run.metric("db")).isEqualTo("# 옛 지표\n");
    }

    @Test
    @DisplayName("덤프가 10KB 미만이면 업로드도 지표 갱신도 하지 않고 실패한다")
    void tinyDumpIsNotABackup(@TempDir Path tmp) throws Exception {
        Run run = run(tmp, List.of("db"), Map.of("FAKE_DUMP_BYTES", "100"));

        assertThat(run.exitCode()).isNotZero();
        assertThat(run.awsCalls()).isEmpty();
        assertThat(run.metric("db")).isNull();
    }

    @Test
    @DisplayName("알 수 없는 모드는 아무것도 하지 않고 실패한다")
    void unknownModeFails(@TempDir Path tmp) throws Exception {
        Run run = run(tmp, List.of("weekly"), Map.of());

        assertThat(run.exitCode()).isNotZero();
        assertThat(run.awsCalls()).isEmpty();
    }

    @Test
    @DisplayName("사진 묶음 만들기(tar)가 치명 오류로 끝나면 최종 이름으로 올리지 않고 비영 종료하며 지표도 쓰지 않는다 — 잘린 묶음이 복원 때 최신 객체가 되지 않게(BR-331)")
    void truncatedPhotoBundleIsNeverPublished(@TempDir Path tmp) throws Exception {
        Run run = run(tmp, List.of("photos"), Map.of("FAKE_TAR_EXIT", "2"));

        assertThat(run.exitCode()).as("출력:\n%s", run.output()).isNotZero();
        assertThat(run.publishedPhotoBundles()).as("묶음이 온전하지 않으면 최종 이름이 생기지 않는다").isEmpty();
        assertThat(run.metric("photos")).isNull();
    }

    @Test
    @DisplayName("tar 가 읽는 사이 파일이 바뀌었다는 경고(종료 코드 1)로 끝나도 묶음은 온전하다 — 최종 이름으로 올리고 성공으로 센다(BR-331)")
    void photoBundleWithChangedFileWarningStillSucceeds(@TempDir Path tmp) throws Exception {
        Run run = run(tmp, List.of("photos"), Map.of("FAKE_TAR_EXIT", "1"));

        assertThat(run.exitCode()).as("출력:\n%s", run.output()).isZero();
        assertThat(run.publishedPhotoBundles()).hasSize(1);
        assertThat(run.metric("photos")).contains(metric("photos") + " ");
    }

    @Test
    @DisplayName("docker exec 자체가 실패하면(backend 가 꺼져 있다 — 종료 코드 1) tar 경고(1)로 오인해 성공으로 세지 않는다(BR-331)")
    void failedExecIsNotMistakenForTarWarning(@TempDir Path tmp) throws Exception {
        Run run = run(tmp, List.of("photos"), Map.of("FAKE_PHOTO_EXEC_FAIL", "1"));

        assertThat(run.exitCode()).isNotZero();
        assertThat(run.publishedPhotoBundles()).isEmpty();
        assertThat(run.metric("photos")).isNull();
    }

    @Test
    @DisplayName("부트스트랩 — DB 는 매시, 사진은 매일 크론이고 데이터 디스크는 비어 있을 때만 포맷한다")
    void bootstrapSchedulesHourlyDbBackupAndNeverReformatsData() throws IOException {
        String script = Files.readString(BOOTSTRAP_SH);

        assertThat(script).contains("0 * * * * root ${APP_DIR}/infra/scripts/backup-db.sh db")
                .contains("10 3 * * * root ${APP_DIR}/infra/scripts/backup-db.sh photos")
                .contains("/var/lib/node_exporter/textfile");
        // 이미 데이터가 든 디스크(인스턴스 교체 때 붙이는 옛 볼륨)를 다시 포맷하면 DB 가 사라진다.
        int blkid = script.indexOf("blkid");
        int mkfs = script.indexOf("mkfs");
        assertThat(blkid).as("파일시스템이 있는지 먼저 본다").isNotNegative().isLessThan(mkfs);
    }

    // ── 실행 도구 ──

    private Run run(Path tmp, List<String> args, Map<String, String> extraEnv) throws Exception {
        return run(tmp, args, extraEnv, null);
    }

    private Run run(Path tmp, List<String> args, Map<String, String> extraEnv, String existingDbMetric) throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path textfile = Files.createDirectories(tmp.resolve("textfile"));
        Path work = Files.createDirectories(tmp.resolve("work"));
        Path awsLog = tmp.resolve("aws.log");
        writeExecutable(bin.resolve("aws"), FAKE_AWS);
        writeExecutable(bin.resolve("docker"), FAKE_DOCKER);
        writeExecutable(bin.resolve("pg_dump"), FAKE_PG_DUMP);
        writeExecutable(bin.resolve("tar"), FAKE_TAR);
        if (existingDbMetric != null) {
            Files.writeString(textfile.resolve("schoolbus_backup_db.prom"), existingDbMetric);
        }

        List<String> command = new java.util.ArrayList<>(List.of("bash", BACKUP_SH.toString()));
        command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        builder.environment().put("APP_DIR", tmp.toString());
        builder.environment().put("TEXTFILE_DIR", textfile.toString());
        builder.environment().put("TMPDIR", work.toString());
        builder.environment().put("BACKUP_BUCKET", "test-bucket");
        builder.environment().put("FAKE_AWS_LOG", awsLog.toString());
        builder.environment().putAll(extraEnv);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("backup-db.sh 가 60초 안에 끝나지 않았다:\n" + output);
        }
        return new Run(process.exitValue(), output, textfile, awsLog);
    }

    private static void writeExecutable(Path file, String content) throws IOException {
        Files.writeString(file, content);
        file.toFile().setExecutable(true);
    }

    /** 실행 결과 — {@code metric(kind)} 는 지표 파일이 없으면 {@code null}. */
    private record Run(int exitCode, String output, Path textfile, Path awsLog) {

        String metric(String kind) throws IOException {
            Path file = textfile.resolve("schoolbus_backup_" + kind + ".prom");
            return Files.exists(file) ? Files.readString(file) : null;
        }

        List<String> awsCalls() throws IOException {
            return Files.exists(awsLog) ? Files.readAllLines(awsLog) : List.of();
        }

        /** 복원이 "최신 객체" 로 집는 최종 이름({@code photos/<시각>.tar.gz})으로 간 업로드·복사의 목적지 — 임시 접두사({@code photos/.partial/})는 뺀다. */
        List<String> publishedPhotoBundles() throws IOException {
            return awsCalls().stream().map(call -> call.substring(call.lastIndexOf(' ') + 1))
                    .filter(destination -> destination.startsWith("s3://test-bucket/photos/") && !destination.startsWith("s3://test-bucket/photos/.partial/"))
                    .toList();
        }
    }

    /**
     * {@code docker compose … exec -T <서비스> <명령…>} 을 흉내 낸다 — 서비스 이름 뒤의 명령을 그대로 실행한다(PATH 앞의 가짜 {@code pg_dump} ·
     * {@code tar} 가 컨테이너 안의 명령을 대신한다). FAKE_PHOTO_EXEC_FAIL 이면 backend 에 대한 exec 자체가 실패한다(컨테이너가 꺼진 경우의 docker 종료 코드 1).
     */
    private static final String FAKE_DOCKER = """
            #!/usr/bin/env bash
            while [ $# -gt 0 ] && [ "$1" != backend ] && [ "$1" != postgres ]; do shift; done
            service="$1"; shift
            [ "$service" = backend ] && [ -n "$FAKE_PHOTO_EXEC_FAIL" ] && exit 1
            exec "$@"
            """;

    /** 덤프는 FAKE_DUMP_BYTES(기본 30000) 바이트의 무작위 데이터. */
    private static final String FAKE_PG_DUMP = """
            #!/usr/bin/env bash
            head -c "${FAKE_DUMP_BYTES:-30000}" /dev/urandom
            """;

    /** 사진 묶음은 5000 바이트를 낸 뒤 FAKE_TAR_EXIT(기본 0)로 끝난다 — 2 는 치명 오류, 1 은 "읽는 사이 파일이 바뀜" 경고. */
    private static final String FAKE_TAR = """
            #!/usr/bin/env bash
            head -c 5000 /dev/urandom
            exit "${FAKE_TAR_EXIT:-0}"
            """;

    /** {@code s3 cp} 호출을 로그에 남기고, 원본이 {@code -} 면 표준입력을 끝까지 읽는다. FAKE_AWS_FAIL 이면 실패한다. */
    private static final String FAKE_AWS = """
            #!/usr/bin/env bash
            [ -n "$FAKE_AWS_FAIL" ] && { echo "fake aws: upload failed" >&2; exit 1; }
            if [ "$1 $2" = "s3 cp" ]; then
              for arg in "$@"; do [ "$arg" = "-" ] && cat > /dev/null; done
              echo "$*" >> "$FAKE_AWS_LOG"
            fi
            exit 0
            """;
}
