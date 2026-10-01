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
 * {@code infra/scripts/render-alertmanager.sh} 를 실제로 실행해 본다 — R46 ops2 `Ruling 480 ④`·`483`.
 *
 * <p>Alertmanager 설정 파일은 환경변수를 읽지 못해 배포 때 SSM 값으로 파일을 만든다. 여기서 막는 사고는 두 가지다 —
 * (1) 값이 <b>하나라도 빠졌는데</b> 반쯤 채워진 수신자를 만들어 Alertmanager 가 기동에 실패하는 것(경보 경로 전체가 죽는다),
 * (2) 값이 <b>전부 없는데</b> 수신자를 만들어 존재하지 않는 계정으로 보내려 시도하는 것. 비어 있으면 수신자 없이 뜨는 것이 맞다.
 */
class AlertmanagerRenderGuardTest {

    private static final Path RENDER_SH = Path.of("..", "infra", "scripts", "render-alertmanager.sh").toAbsolutePath().normalize();

    private static final String TOKEN = "123456789:AAE-secret_token";

    private static Map<String, String> telegram() {
        Map<String, String> env = new HashMap<>();
        env.put("ALERT_TELEGRAM_BOT_TOKEN", TOKEN);
        env.put("ALERT_TELEGRAM_CHAT_ID", "-1001234567890");
        return env;
    }

    private static Map<String, String> email() {
        Map<String, String> env = new HashMap<>();
        env.put("ALERT_EMAIL_TO", "ops@example.com");
        env.put("ALERT_SMTP_HOST", "smtp.example.com:587");
        env.put("ALERT_SMTP_USER", "alert@example.com");
        env.put("ALERT_SMTP_PASSWORD", "smtp-secret-pass");
        return env;
    }

    @Test
    @DisplayName("값이 하나도 없으면 수신자 없이 만든다 — 어디로도 보내지 않고 기동은 된다")
    void noValuesMeansNoReceiverConfigs(@TempDir Path tmp) throws Exception {
        Run run = render(tmp, Map.of());

        assertThat(run.exitCode()).as("출력:\n%s", run.output()).isZero();
        assertThat(run.yml()).contains("receiver: notify").doesNotContain("telegram_configs").doesNotContain("email_configs");
    }

    @Test
    @DisplayName("텔레그램 값만 있으면 텔레그램 수신자만 만들고 이메일은 만들지 않는다")
    void telegramOnly(@TempDir Path tmp) throws Exception {
        Run run = render(tmp, telegram());

        assertThat(run.exitCode()).as("출력:\n%s", run.output()).isZero();
        assertThat(run.yml()).contains("telegram_configs").contains("bot_token: '" + TOKEN + "'").contains("chat_id: -1001234567890")
                .doesNotContain("email_configs");
        assertThat(run.output()).as("비밀값을 출력에 싣지 않는다").doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("이메일 값만 있으면 이메일 수신자만 만들고 발신 주소는 SMTP 계정과 같다")
    void emailOnly(@TempDir Path tmp) throws Exception {
        Run run = render(tmp, email());

        assertThat(run.exitCode()).as("출력:\n%s", run.output()).isZero();
        assertThat(run.yml()).contains("email_configs").contains("to: 'ops@example.com'").contains("from: 'alert@example.com'")
                .contains("smarthost: 'smtp.example.com:587'").contains("auth_password: 'smtp-secret-pass'")
                .doesNotContain("telegram_configs");
        assertThat(run.output()).doesNotContain("smtp-secret-pass");
    }

    @Test
    @DisplayName("둘 다 있으면 한 수신자에 두 채널을 모두 둔다 — 한쪽이 막혀도 다른 쪽으로 닿는다")
    void bothChannelsInOneReceiver(@TempDir Path tmp) throws Exception {
        Map<String, String> env = telegram();
        env.putAll(email());
        Run run = render(tmp, env);

        assertThat(run.exitCode()).isZero();
        assertThat(run.yml()).contains("telegram_configs").contains("email_configs");
        assertThat(run.yml().split("- name: ")).as("수신자는 하나").hasSize(2);
    }

    static Stream<Arguments> brokenValues() {
        Map<String, String> tokenOnly = new HashMap<>(Map.of("ALERT_TELEGRAM_BOT_TOKEN", TOKEN));
        Map<String, String> chatOnly = new HashMap<>(Map.of("ALERT_TELEGRAM_CHAT_ID", "123"));
        Map<String, String> badChat = telegram();
        badChat.put("ALERT_TELEGRAM_CHAT_ID", "my-channel");
        Map<String, String> badToken = telegram();
        badToken.put("ALERT_TELEGRAM_BOT_TOKEN", "not-a-token");
        Map<String, String> noPassword = email();
        noPassword.remove("ALERT_SMTP_PASSWORD");
        Map<String, String> noHostPort = email();
        noHostPort.put("ALERT_SMTP_HOST", "smtp.example.com");
        Map<String, String> quote = email();
        quote.put("ALERT_SMTP_PASSWORD", "pa'ss");
        return Stream.of(
                Arguments.of("텔레그램 토큰만 있다", tokenOnly, "ALERT_TELEGRAM_CHAT_ID"),
                Arguments.of("텔레그램 채팅 ID 만 있다", chatOnly, "ALERT_TELEGRAM_BOT_TOKEN"),
                Arguments.of("채팅 ID 가 숫자가 아니다", badChat, "ALERT_TELEGRAM_CHAT_ID"),
                Arguments.of("봇 토큰 형식이 아니다", badToken, "ALERT_TELEGRAM_BOT_TOKEN"),
                Arguments.of("SMTP 비밀번호가 빠졌다", noPassword, "ALERT_SMTP_PASSWORD"),
                Arguments.of("SMTP 주소에 포트가 없다", noHostPort, "ALERT_SMTP_HOST"),
                Arguments.of("값에 작은따옴표가 있다(YAML 인용이 깨진다)", quote, "ALERT_SMTP_PASSWORD"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("brokenValues")
    @DisplayName("값이 반쪽이거나 형식이 틀리면 파일을 만들지 않고 어느 값인지 알리며 멈춘다(값은 출력하지 않는다)")
    void brokenValuesStopWithoutWriting(String caseName, Map<String, String> env, String expectedName, @TempDir Path tmp)
            throws Exception {
        Run run = render(tmp, env);

        assertThat(run.exitCode()).as("출력:\n%s", run.output()).isNotZero();
        assertThat(run.output()).contains(expectedName).doesNotContain(TOKEN).doesNotContain("smtp-secret-pass").doesNotContain("pa'ss");
        assertThat(run.yml()).as("멈춘 렌더링은 기존 파일을 건드리지 않는다").isNull();
    }

    @Test
    @DisplayName("배포 스크립트가 이 렌더러를 호출하고 Alertmanager 에 새 설정을 다시 읽히며, 컴포즈는 렌더된 폴더를 마운트한다")
    void deployWiresRenderAndReload() throws IOException {
        String deploy = Files.readString(Path.of("..", "infra", "scripts", "deploy.sh"));
        String compose = Files.readString(Path.of("..", "docker-compose.prod.yml"));

        assertThat(deploy).contains("render-alertmanager.sh").contains("kill -s HUP alertmanager");
        assertThat(compose).contains("./alertmanager:/etc/alertmanager:ro");
    }

    // ── 실행 도구 ──

    private Run render(Path tmp, Map<String, String> env) throws Exception {
        Path out = tmp.resolve("alertmanager").resolve("alertmanager.yml");
        ProcessBuilder builder = new ProcessBuilder(List.of("bash", RENDER_SH.toString(), out.toString()));
        builder.redirectErrorStream(true);
        builder.environment().putAll(env);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("render-alertmanager.sh 가 30초 안에 끝나지 않았다:\n" + output);
        }
        return new Run(process.exitValue(), output, Files.exists(out) ? Files.readString(out) : null);
    }

    /** 실행 결과 — {@code yml} 은 만들어지지 않았으면 {@code null}. */
    private record Run(int exitCode, String output, String yml) {
    }
}
