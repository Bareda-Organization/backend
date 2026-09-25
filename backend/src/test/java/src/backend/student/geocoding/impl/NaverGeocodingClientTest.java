package src.backend.student.geocoding.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.reactive.function.client.WebClient;

import com.sun.net.httpserver.HttpServer;

import src.backend.student.geocoding.spec.GeocodedPoint;

/**
 * 네이버 지오코딩 응답의 좌표를 API 규격 자릿수(§1.1 소수 6자리)로 맞추는지 본다(BR-123).
 *
 * <p>네이버는 {@code "37.5087360"} 처럼 7자리를 줄 수 있다. 그대로 넘기면 저장 직후 응답(7자리)과 다시 읽은
 * 응답({@code numeric(9,6)} 반올림, 6자리)의 같은 칸 좌표가 서로 달라진다. 공급자 자리에 로컬 HTTP 서버를
 * 세우고 앱의 {@link WebClient} 빈으로 부른다({@code NaverPlaceSearchClientTest} 와 같은 이유).
 */
@SpringBootTest
class NaverGeocodingClientTest {

    @Autowired
    private WebClient webClient;

    private HttpServer provider;

    @AfterEach
    void stop() {
        provider.stop(0);
    }

    @Test
    void 좌표를_소수_6자리로_맞춘다() throws IOException {
        NaverGeocodingClient client = clientAnswering("""
                {"addresses":[{"roadAddress":"서울특별시 강남구 테헤란로 1","jibunAddress":"",
                  "x":"127.0276213","y":"37.5087360"}]}""");

        GeocodedPoint point = client.geocode("테헤란로 1").orElseThrow();
        GeocodedPoint candidate = client.candidates("테헤란로 1").getFirst();

        assertThat(point.lat()).isEqualTo(new BigDecimal("37.508736"));
        assertThat(point.lng()).isEqualTo(new BigDecimal("127.027621"));
        assertThat(candidate.lat()).isEqualTo(new BigDecimal("37.508736"));
        assertThat(candidate.lng()).isEqualTo(new BigDecimal("127.027621"));
    }

    private NaverGeocodingClient clientAnswering(String body) throws IOException {
        provider = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        provider.createContext("/", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        provider.start();
        return new NaverGeocodingClient(webClient, "http://localhost:" + provider.getAddress().getPort(), "id",
                "key");
    }
}
