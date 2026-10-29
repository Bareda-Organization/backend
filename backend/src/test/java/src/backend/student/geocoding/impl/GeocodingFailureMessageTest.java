package src.backend.student.geocoding.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import reactor.core.publisher.Mono;
import src.backend.student.geocoding.spec.GeocodingUnavailableException;

/**
 * 공급자 호출이 실패해 올라가는 예외에 주소·검색어가 실리지 않는다 — 예외 메시지와 원인 사슬은 로그로 그대로 나가는데, 주소는 L3 개인정보다.
 * 실패의 종류(원인 클래스 · HTTP 상태)는 남아 있어야 운영자가 원인을 가를 수 있다.
 */
class GeocodingFailureMessageTest {

    private static final String SECRET = "서울시 비밀로 77";

    private static final WebClientResponseException PROVIDER_503 = WebClientResponseException.create(503,
            "Service Unavailable", HttpHeaders.EMPTY, new byte[0], null);

    private static final RuntimeException CARRIES_SECRET = new IllegalStateException("GET http://naver/geocode?query=" + SECRET);

    private final NaverGeocodingClient geocoding = new NaverGeocodingClient(WebClient.create(), "http://localhost:1", "", "");

    private final NaverPlaceSearchClient places = new NaverPlaceSearchClient(WebClient.create(), "http://localhost:1", "", "");

    @Test
    void 주소_지오코딩_실패_예외에_주소가_없다() {
        GeocodingUnavailableException thrown = catchThrowableOfType(GeocodingUnavailableException.class,
                () -> geocoding.unavailable(SECRET, CARRIES_SECRET));

        assertNoSecret(thrown);
        assertThat(thrown.getMessage()).contains("IllegalStateException");
    }

    @Test
    void 자동완성_실패_예외에_검색어가_없다() {
        GeocodingUnavailableException thrown = catchThrowableOfType(GeocodingUnavailableException.class,
                () -> geocoding.candidatesUnavailable(SECRET, CARRIES_SECRET));

        assertNoSecret(thrown);
    }

    @Test
    void 장소_검색_차단_예외에_검색어가_없다() {
        GeocodingUnavailableException thrown = catchThrowableOfType(GeocodingUnavailableException.class,
                () -> places.unavailable(SECRET, CARRIES_SECRET));

        assertNoSecret(thrown);
    }

    @Test
    void 장소_검색_호출_실패_예외에_검색어가_없고_상태는_남는다() {
        NaverPlaceSearchClient failing = new NaverPlaceSearchClient(WebClient.builder()
                .exchangeFunction(request -> Mono.<org.springframework.web.reactive.function.client.ClientResponse>error(
                        new IllegalStateException("요청 " + request.url())))
                .build(), "http://localhost:1", "", "");

        GeocodingUnavailableException thrown = catchThrowableOfType(GeocodingUnavailableException.class,
                () -> failing.search(SECRET));

        assertNoSecret(thrown);
    }

    @Test
    void 공급자_상태_코드는_예외_메시지에_남는다() {
        GeocodingUnavailableException thrown = catchThrowableOfType(GeocodingUnavailableException.class,
                () -> geocoding.unavailable(SECRET, PROVIDER_503));

        assertNoSecret(thrown);
        assertThat(thrown.getMessage()).contains("503");
    }

    private static void assertNoSecret(Throwable thrown) {
        assertThat(thrown).isNotNull();
        StringBuilder everything = new StringBuilder();
        for (Throwable link = thrown; link != null; link = link.getCause()) {
            everything.append(link).append('\n');
        }
        assertThat(everything.toString()).as("예외 메시지·원인 사슬에 주소가 실리면 로그로 그대로 나간다")
                .doesNotContain(SECRET).doesNotContain("비밀로");
    }
}
