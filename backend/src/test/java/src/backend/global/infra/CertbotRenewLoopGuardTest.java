package src.backend.global.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code infra/certbot/renew-loop.sh}(certbot 컨테이너의 갱신 루프)를 가짜 {@code certbot} 으로 실제 실행해 본다 — BR-334 (R10-11).
 *
 * <p>갱신이 막혀도 컨테이너는 계속 돌고 결과는 로그에만 남아, 90일 인증서가 만료되는 날에야 모든 클라이언트의 HTTPS 가 한꺼번에 거절된다. 그래서 갱신이
 * <b>오류 없이 끝났을 때만</b> 성공 시각 지표(node-exporter textfile)를 쓰고, 경보 {@code CertbotRenewStale} 이 그 시각이 오래되면 울린다 —
 * 실패했는데 지표가 갱신되면 갱신이 막힌 채 초록이다.
 */
class CertbotRenewLoopGuardTest {

    private static final Path RENEW_LOOP_SH = Path.of("..", "infra", "certbot", "renew-loop.sh").toAbsolutePath().normalize();

    private static final String METRIC = "schoolbus_certbot_renew_last_success_timestamp_seconds";

    @Test
    @DisplayName("certbot renew 가 성공하면(갱신할 때가 아니어서 아무것도 안 한 경우 포함) 성공 시각 지표를 현재 시각으로 쓴다")
    void successfulRenewalWritesTheMetric(@TempDir Path tmp) throws Exception {
        Run run = run(tmp, "0", null);

        assertThat(run.exitCode()).as("출력:\n%s", run.output()).isZero();
        assertThat(run.certbotCalls()).containsExactly("renew --webroot -w /var/www/certbot --quiet");
        long written = Long.parseLong(run.metric().lines().filter(line -> line.startsWith(METRIC + " ")).findFirst().orElseThrow().split(" ")[1]);
        assertThat(written).as("지표 값은 지금 시각(epoch 초)이다").isBetween(System.currentTimeMillis() / 1000 - 60, System.currentTimeMillis() / 1000 + 60);
    }

    @Test
    @DisplayName("certbot renew 가 실패하면 지표를 갱신하지 않는다 — 옛 시각이 그대로 남아 경보가 갱신 정지를 본다")
    void failedRenewalLeavesTheMetricUntouched(@TempDir Path tmp) throws Exception {
        Run run = run(tmp, "1", "# 옛 지표\n");

        assertThat(run.metric()).isEqualTo("# 옛 지표\n");
        assertThat(run.output()).as("실패는 로그에 남는다 — CloudWatch").contains("실패");
    }

    @Test
    @DisplayName("certbot renew 가 실패해도 지표 파일을 새로 만들지 않는다 — 한 번도 성공하지 못했으면 지표 부재(absent)로 경보가 본다")
    void failedFirstRenewalWritesNoMetricFile(@TempDir Path tmp) throws Exception {
        assertThat(run(tmp, "1", null).metric()).isNull();
    }

    // ── 실행 도구 ──

    private Run run(Path tmp, String certbotExit, String existingMetric) throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path textfile = Files.createDirectories(tmp.resolve("textfile"));
        Path certbotLog = tmp.resolve("certbot.log");
        Files.writeString(bin.resolve("certbot"), "#!/bin/sh\necho \"$*\" >> \"$FAKE_CERTBOT_LOG\"\nexit \"$FAKE_CERTBOT_EXIT\"\n");
        bin.resolve("certbot").toFile().setExecutable(true);
        if (existingMetric != null) {
            Files.writeString(textfile.resolve("schoolbus_certbot.prom"), existingMetric);
        }

        ProcessBuilder builder = new ProcessBuilder("sh", RENEW_LOOP_SH.toString());
        builder.redirectErrorStream(true);
        builder.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        builder.environment().put("TEXTFILE_DIR", textfile.toString());
        builder.environment().put("RENEW_ONCE", "1");
        builder.environment().put("FAKE_CERTBOT_LOG", certbotLog.toString());
        builder.environment().put("FAKE_CERTBOT_EXIT", certbotExit);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("renew-loop.sh 가 30초 안에 끝나지 않았다(RENEW_ONCE=1 이면 한 번만 돌고 끝난다):\n" + output);
        }
        return new Run(process.exitValue(), output, textfile, certbotLog);
    }

    /** 실행 결과 — {@code metric()} 은 지표 파일이 없으면 {@code null}. */
    private record Run(int exitCode, String output, Path textfile, Path certbotLog) {

        String metric() throws IOException {
            Path file = textfile.resolve("schoolbus_certbot.prom");
            return Files.exists(file) ? Files.readString(file) : null;
        }

        List<String> certbotCalls() throws IOException {
            return Files.exists(certbotLog) ? Files.readAllLines(certbotLog) : List.of();
        }
    }
}
