package src.backend.global.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * 스테이징 프록시(BR-230) — Cloudflare Tunnel 로 인터넷에 공개되는 경로라 운영 프록시({@code nginx.prod.conf})의
 * 보호 3가지(인증 경로 속도 제한 · Swagger 차단 · 감사 IP)가 빠지면 안 된다. 개발용 {@code nginx.conf} 를 그대로 마운트하던
 * 것이 결함이었고, 설정 문법은 {@code docker run --rm nginx:alpine nginx -t} 로 따로 본다(컨테이너를 띄우지 않는다).
 */
class StagingProxyConfigTest {

    private static final Path PROXY_DIR = Path.of("..", "infra", "proxy");

    private static final Pattern AUTH_LOCATION = Pattern.compile("location ~ \\^/api/v1/\\(auth/[^\\n]*\\{");

    private static String read(String relative) throws IOException {
        return Files.readString(PROXY_DIR.resolve(relative).normalize());
    }

    @Test
    void 스테이징_프록시는_운영과_같은_인증_경로에_속도_제한을_건다() throws IOException {
        String prod = read("nginx.prod.conf");
        String staging = read("nginx.staging.conf");

        Matcher prodAuth = AUTH_LOCATION.matcher(prod);
        Matcher stagingAuth = AUTH_LOCATION.matcher(staging);
        assertThat(prodAuth.find()).as("운영 설정에 인증 경로 location 이 있어야 비교할 수 있다").isTrue();
        assertThat(stagingAuth.find()).as("스테이징 설정에 인증 경로 location 이 있어야 한다").isTrue();
        assertThat(stagingAuth.group()).as("운영과 같은 경로 목록이어야 한다 — 한쪽만 고치면 스테이징이 조용히 뒤처진다")
                .isEqualTo(prodAuth.group());
        String block = staging.substring(stagingAuth.end(), staging.indexOf('}', stagingAuth.end()));
        assertThat(block).contains("limit_req zone=login_zone").contains("limit_req_status 429");
        assertThat(staging).contains("limit_req_zone $binary_remote_addr zone=login_zone");
    }

    @Test
    void 스테이징_프록시는_Swagger_를_공개하지_않는다() throws IOException {
        String staging = read("nginx.staging.conf");

        int start = staging.indexOf("location ~ ^/(swagger-ui|v3/api-docs)");
        assertThat(start).as("Swagger location 이 있어야 404 로 막을 수 있다").isNotNegative();
        String block = staging.substring(start, staging.indexOf('}', start));
        assertThat(block).contains("return 404").doesNotContain("proxy_pass");
    }

    @Test
    void 스테이징_프록시는_터널_뒤_실제_IP_를_신뢰_목록과_함께_읽는다() throws IOException {
        String staging = read("nginx.staging.conf");

        assertThat(staging).contains("real_ip_header CF-Connecting-IP").contains("set_real_ip_from");
        assertThat(staging).as("신뢰 목록 없이 헤더만 믿으면 아무나 감사 IP 를 위조한다").doesNotContain("set_real_ip_from 0.0.0.0/0");
    }

    @Test
    void 스테이징_compose_는_스테이징_프록시_설정을_마운트한다() throws IOException {
        String compose = Files.readString(Path.of("..", "docker-compose.staging.yml"));

        assertThat(compose).contains("./infra/proxy/nginx.staging.conf:/etc/nginx/conf.d/default.conf:ro");
        assertThat(compose).doesNotContain("./infra/proxy/nginx.conf:");
    }
}
