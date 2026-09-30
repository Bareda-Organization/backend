package testsupport.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

import com.sun.net.httpserver.HttpServer;

/**
 * 실 Directions 시험의 "한도 초과면 건너뜀" 판정 — 한도 초과 응답에만 건너뛰고, 같은 400 이라도
 * 다른 사유(예: 출발지·도착지 동일)는 건너뛰지 않아야 한다. 모든 400 을 건너뛰면 실 API 형태가 어긋나도
 * 라이브 시험이 조용히 초록이 되기 때문이다.
 */
class DirectionsQuotaTest {

    private static final String QUOTA_BODY = "{\"error\":{\"errorCode\":\"400\",\"message\":\"사용량이 한도를 초과했습니다\"}}";

    private static final String SAME_POINT_BODY = "{\"code\":1,\"message\":\"출발지와 도착지가 동일합니다\"}";

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void 한도_초과_문구가_있는_400_만_한도_초과로_판정한다() {
        assertThat(DirectionsQuota.isQuotaExceeded(400, QUOTA_BODY)).isTrue();
        assertThat(DirectionsQuota.isQuotaExceeded(400, SAME_POINT_BODY)).isFalse();
        assertThat(DirectionsQuota.isQuotaExceeded(200, "{}")).isFalse();
        assertThat(DirectionsQuota.isQuotaExceeded(500, QUOTA_BODY)).isFalse();
    }

    @Test
    void 한도_초과_응답이면_시험을_건너뛴다() throws IOException {
        String base = serve(400, QUOTA_BODY);

        assertThatThrownBy(() -> DirectionsQuota.assumeQuotaLeft(base, "/driving", "id", "key"))
                .isInstanceOf(TestAbortedException.class);
    }

    @Test
    void 다른_사유의_400_은_건너뛰지_않는다() throws IOException {
        String base = serve(400, SAME_POINT_BODY);

        assertThatCode(() -> DirectionsQuota.assumeQuotaLeft(base, "/driving", "id", "key")).doesNotThrowAnyException();
    }

    @Test
    void 정상_응답이면_건너뛰지_않는다() throws IOException {
        String base = serve(200, "{}");

        assertThatCode(() -> DirectionsQuota.assumeQuotaLeft(base, "/driving", "id", "key")).doesNotThrowAnyException();
    }

    @Test
    void 연결이_안_되면_건너뛰지_않는다() {
        assertThatCode(() -> DirectionsQuota.assumeQuotaLeft("http://localhost:1", "/driving", "id", "key"))
                .doesNotThrowAnyException();
    }

    private String serve(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://localhost:" + server.getAddress().getPort();
    }
}
