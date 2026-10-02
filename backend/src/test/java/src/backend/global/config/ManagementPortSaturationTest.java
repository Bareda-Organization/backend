package src.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;

/**
 * R46 누수 검토 R-3(Ruling 740) — 앱 커넥터가 동시 연결 상한({@code server.tomcat.max-connections}, Ruling 690)에 닿아도 <b>관리 포트의
 * 헬스·지표는 응답</b>한다. 상한에 닿으면 Tomcat 은 새 연결을 받지 않으므로 같은 커넥터에 있던 헬스·로그인이 함께 응답이 없었다 —
 * 서버는 살아 있는데 운영자가 그 사실도, 포화 지표도 못 보는 상태다.
 *
 * <p>상한을 20 으로 줄인 실서버(RANDOM_PORT)에서 앱 포트 연결을 상한만큼 쥐고 본다. 같은 서버의 앱 포트 {@code /healthz} 가 응답이 없는 것이
 * "정말 가득 찼다" 는 대조군이라, 관리 포트가 200 인 것이 포화가 안 걸려서 생긴 우연이 아님을 보인다. 운영 프로파일(prod·demo·staging)이
 * 관리 포트를 따로 여는지는 {@code DeploymentConfigGuardTest} 가 설정 파일로 고정한다 — 이 시험은 그 설정이 만드는 동작을 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "server.tomcat.max-connections=" + ManagementPortSaturationTest.CAP,
        "server.tomcat.accept-count=1",
        // 쥐고 있는 소켓이 시험 도중 시간 초과로 닫히지 않게 한다(기본 20초)
        "server.tomcat.connection-timeout=60s"})
class ManagementPortSaturationTest {

    static final int CAP = 20;

    private static final Duration NO_RESPONSE_WITHIN = Duration.ofSeconds(2);

    private static final Pattern CURRENT_CONNECTIONS = Pattern
            .compile("(?m)^tomcat_connections_current_connections\\{[^}]*\\} (\\d+)\\.0$");

    @LocalServerPort
    private int appPort;

    @Autowired
    private Environment environment;

    @Test
    @DisplayName("앱 포트가 연결 상한으로 가득 차도 관리 포트의 헬스·지표는 200 이고 포화가 지표로 보인다")
    void managementPortAnswersWhileAppConnectorIsFull() throws Exception {
        int managementPort = environment.getRequiredProperty("local.management.port", Integer.class);
        assertThat(managementPort).as("관리 포트는 앱 포트와 다른 커넥터다").isNotEqualTo(appPort);
        assertThat(get(appPort, "/healthz").status()).as("채우기 전에는 앱 포트 헬스도 응답한다(대조군이 성립하는 조건)").isEqualTo(200);

        List<Socket> held = new ArrayList<>();
        try {
            // 상한보다 몇 개 더 시도한다 — 상한을 넘은 연결은 OS 대기열(accept-count 1)에서 기다리다 거절되는데 그 모습(거부·시간 초과)은 OS 마다 달라 무시한다.
            // 가득 찼는지는 아래에서 Tomcat 의 현재 연결 수로 판정한다.
            for (int i = 0; i < CAP + 2; i++) {
                Socket socket = new Socket();
                try {
                    socket.connect(new InetSocketAddress("localhost", appPort), 1_000);
                    held.add(socket);
                } catch (IOException refusedBeyondCap) {
                    socket.close();
                }
            }
            Awaitility.await("앱 커넥터의 현재 연결 수가 상한에 닿는다").atMost(Duration.ofSeconds(10))
                    .pollInterval(Duration.ofMillis(200))
                    .untilAsserted(() -> assertThat(currentConnections(managementPort)).isEqualTo(CAP));

            assertThatThrownBy(() -> get(appPort, "/healthz"))
                    .as("앱 포트는 새 연결을 받지 못해 헬스도 응답이 없다 — 가득 찬 상태가 실제로 만들어졌다는 대조군")
                    .isInstanceOf(IOException.class);

            Response health = get(managementPort, "/actuator/health");
            assertThat(health.status()).as("관리 포트 헬스").isEqualTo(200);
            assertThat(health.body()).contains("\"status\":\"UP\"");
            assertThat(get(managementPort, "/actuator/prometheus").status()).as("관리 포트 지표").isEqualTo(200);
        } finally {
            for (Socket socket : held) {
                socket.close();
            }
        }

        Awaitility.await("쥐던 연결을 놓으면 앱 포트 헬스가 돌아온다").atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(get(appPort, "/healthz").status()).isEqualTo(200));
    }

    private int currentConnections(int managementPort) throws IOException {
        Matcher gauge = CURRENT_CONNECTIONS.matcher(get(managementPort, "/actuator/prometheus").body());
        assertThat(gauge.find()).as("tomcat_connections_current_connections 지표가 노출된다").isTrue();
        return Integer.parseInt(gauge.group(1));
    }

    /** 연결을 재사용하지 않는 한 번짜리 GET — 시험이 쥔 연결 수에 이 요청이 섞이지 않게 {@code Connection: close} 로 보낸다. */
    private Response get(int port, String path) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create("http://localhost:" + port + path).toURL().openConnection();
        connection.setConnectTimeout((int) NO_RESPONSE_WITHIN.toMillis());
        connection.setReadTimeout((int) NO_RESPONSE_WITHIN.toMillis());
        connection.setRequestProperty("Connection", "close");
        try {
            int status = connection.getResponseCode();
            var stream = status < 400 ? connection.getInputStream() : connection.getErrorStream();
            return new Response(status, stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            connection.disconnect();
        }
    }

    private record Response(int status, String body) {
    }
}
