package src.backend.global.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 스테이징 프록시가 터널 뒤 접속자 IP 를 어느 헤더에서 읽는지 — 터널마다 신뢰하는 헤더가 다르다. ngrok 은 {@code CF-Connecting-IP} 를 모르므로 접속자가 그 헤더를 보내면 위조된 값이
 * 접속 주소가 되어 로그인 속도 제한을 피하고 감사 IP 를 속인다. Cloudflare 는 {@code X-Forwarded-For} 앞에 접속자가 값을 붙일 수 있다. 그래서 파일마다 읽는 헤더를 못 박고,
 * compose 의 기본 파일이 ngrok 용인지 본다. 주석 줄은 걷어 내고 본다 — 주석이 옛 설정을 설명해도 통과하지 않게.
 */
class ProxyRealIpConfigGuardTest {

    private static final Path PROXY = Path.of("..", "infra", "proxy");

    private static final Path STAGING_COMPOSE = Path.of("..", "docker-compose.staging.yml");

    @Test
    @DisplayName("ngrok 용 파일은 X-Forwarded-For 의 마지막 값만 읽고 CF-Connecting-IP 를 읽지 않는다")
    void ngrokFileTrustsOnlyTheLastForwardedFor() throws IOException {
        String directives = directives(PROXY.resolve("real-ip.ngrok.conf"));

        assertThat(directives).contains("real_ip_header X-Forwarded-For;").contains("real_ip_recursive off;")
                .doesNotContain("CF-Connecting-IP");
    }

    @Test
    @DisplayName("Cloudflare 용 파일은 CF-Connecting-IP 만 읽고 X-Forwarded-For 를 읽지 않는다")
    void cloudflareFileTrustsOnlyTheConnectingIpHeader() throws IOException {
        String directives = directives(PROXY.resolve("real-ip.cloudflare.conf"));

        assertThat(directives).contains("real_ip_header CF-Connecting-IP;").doesNotContain("X-Forwarded-For");
    }

    @Test
    @DisplayName("두 파일 모두 신뢰 대역을 사설 대역으로만 연다 — 인터넷 주소를 신뢰 프록시로 두지 않는다")
    void trustedRangesArePrivateOnly() throws IOException {
        for (String file : new String[] {"real-ip.ngrok.conf", "real-ip.cloudflare.conf"}) {
            Matcher range = Pattern.compile("set_real_ip_from\\s+(\\S+);").matcher(directives(PROXY.resolve(file)));
            int found = 0;
            while (range.find()) {
                found++;
                assertThat(range.group(1)).as("%s — 신뢰 대역", file).isIn("172.16.0.0/12", "10.0.0.0/8", "192.168.0.0/16");
            }
            assertThat(found).as("%s 에 신뢰 대역이 있다", file).isPositive();
        }
    }

    @Test
    @DisplayName("스테이징 compose 의 기본 접속자 IP 파일은 ngrok 용이고 nginx 설정이 그 파일을 포함한다")
    void stagingComposeDefaultsToTheNgrokFile() throws IOException {
        String compose = stripComments(Files.readString(STAGING_COMPOSE));
        String nginx = stripComments(Files.readString(PROXY.resolve("nginx.staging.conf")));

        assertThat(compose).contains("${STAGING_REAL_IP_CONF:-./infra/proxy/real-ip.ngrok.conf}:/etc/nginx/real-ip.conf");
        assertThat(nginx).contains("include /etc/nginx/real-ip.conf;");
    }

    private static String directives(Path file) throws IOException {
        return stripComments(Files.readString(file));
    }

    private static String stripComments(String text) {
        return text.replaceAll("(?m)^\\s*#.*$", "").replaceAll("(?m)\\s+#.*$", "");
    }
}
