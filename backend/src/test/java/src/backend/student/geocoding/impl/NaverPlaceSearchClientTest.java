package src.backend.student.geocoding.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.reactive.function.client.WebClient;

import com.sun.net.httpserver.HttpServer;

import src.backend.student.geocoding.spec.GeocodingUnavailableException;
import src.backend.student.geocoding.spec.PlaceSearchClient.FoundPlace;

/**
 * NAVER API HUB 지역 검색 응답을 장소 후보로 옮기는 어댑터(2026-09-23 실측 응답 모양 그대로).
 *
 * <p>좌표가 <b>경위도 × 10⁷ 정수 문자열</b>로 온다 — 자릿수를 한 칸만 틀려도 후보가 수십 km 밖(또는 바다)에
 * 찍히는데, 화면에는 그럴듯한 주소가 함께 떠서 눈으로 알아채기 어렵다. 로컬 HTTP 서버를 공급자 자리에 세워
 * 실제 응답 그대로 넘긴다.
 *
 * <p>⚠ <b>앱이 실제로 쓰는 {@link WebClient} 빈</b>으로 부른다. 처음엔 {@code WebClient.create()} 로 시험해
 * 초록이었는데 운영에서 전부 실패했다(2026-09-23) — ①공급자가 JSON 을 {@code text/plain} 으로 보내고
 * ②앱의 JSON 설정이 필드 이름을 snake_case 로 바꿔 {@code roadAddress} 가 비었다. 둘 다 기본 클라이언트로는
 * 안 보인다.
 */
@SpringBootTest
class NaverPlaceSearchClientTest {

    /** 실측 응답 머리 — JSON 인데 {@code text/plain} 이다. */
    private static final String PROVIDER_CONTENT_TYPE = "text/plain;charset=UTF-8";

    @Autowired
    private WebClient webClient;

    private HttpServer provider;

    @AfterEach
    void stop() {
        provider.stop(0);
    }

    @Test
    void 좌표를_경위도로_옮기고_제목의_강조_태그를_걷어낸다() throws IOException {
        NaverPlaceSearchClient client = clientAnswering(200, """
                {"items":[{"title":"<b>현대백화점</b> <b>목동</b>점","address":"서울특별시 양천구 목동 916",
                  "roadAddress":"서울특별시 양천구 목동동로 257","mapx":"1268752870","mapy":"375264557"}]}""");

        List<FoundPlace> places = client.search("목동 현대백화점");

        assertThat(places).hasSize(1);
        assertThat(places.getFirst().name()).isEqualTo("현대백화점 목동점");
        assertThat(places.getFirst().point().lat()).isEqualByComparingTo(new BigDecimal("37.526456"));
        assertThat(places.getFirst().point().lng()).isEqualByComparingTo(new BigDecimal("126.875287"));
        assertThat(places.getFirst().point().displayName()).isEqualTo("서울특별시 양천구 목동동로 257");
    }

    /** 키가 틀렸거나 비었으면 401 이다 — 결과 0건(빈 목록)과 갈라, 호출자가 "지금 못 부른다" 로 처리하게 한다. */
    @Test
    void 인증_실패는_빈_목록이_아니라_공급자_장애다() throws IOException {
        NaverPlaceSearchClient client = clientAnswering(401,
                "{\"error\":{\"errorCode\":\"200\",\"message\":\"Authentication Failed\"}}");

        assertThatThrownBy(() -> client.search("신정역")).isInstanceOf(GeocodingUnavailableException.class);
    }

    private NaverPlaceSearchClient clientAnswering(int status, String body) throws IOException {
        provider = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        provider.createContext("/search/v1/local", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", PROVIDER_CONTENT_TYPE);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        provider.start();
        return new NaverPlaceSearchClient(webClient,
                "http://localhost:" + provider.getAddress().getPort(), "id", "key");
    }
}
