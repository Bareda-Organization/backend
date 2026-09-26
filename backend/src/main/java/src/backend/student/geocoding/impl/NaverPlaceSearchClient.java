package src.backend.student.geocoding.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;

import src.backend.student.geocoding.spec.GeocodedPoint;
import src.backend.student.geocoding.spec.GeocodingUnavailableException;
import src.backend.student.geocoding.spec.PlaceSearchClient;

/**
 * NAVER API HUB 지역 검색(2026-09-23 실측) — 개발자센터 검색 API 가 2026-07-31 부터 신규 발급을 멈추고
 * 네이버 클라우드 플랫폼 HUB 로 옮겨졌다.
 *
 * <p>⚠ 헤더는 NCP 방식({@code x-ncp-apigw-api-key-id}·{@code x-ncp-apigw-api-key})이다 — 옛
 * {@code X-Naver-Client-Id} 로 보내면 {@code 401 Authentication Failed}(실측). 좌표는 {@code mapx}·
 * {@code mapy} 에 <b>경위도 × 10⁷ 정수</b>로 온다. 한 번에 최대 5건이다. 본문은 JSON 인데 머리는
 * {@code text/plain} 이다.
 *
 * <p>재시도를 두지 않는다 — 자동완성의 보조 후보라 실패하면 주소 후보만 보이면 되고, 입력마다
 * 부르는 호출에 재시도를 얹으면 느린 순간이 길어질 뿐이다. 대신 상한 시간을 짧게 둔다. 서킷은 둔다
 * (§7 규칙 11 · BR-163) — 공급자 장애 동안 입력마다 상한 시간(3초)을 기다리지 않고 곧바로 주소 후보로 넘어간다.
 */
@Component
@ConditionalOnProperty(name = "app.place-search.provider", havingValue = "naver", matchIfMissing = true)
public class NaverPlaceSearchClient implements PlaceSearchClient {

    /** Resilience4j 인스턴스 이름({@code application.yml}) — 지오코딩과 다른 상품이라 서킷을 가른다. */
    public static final String RESILIENCE_INSTANCE = "placeSearch";

    private static final String LOCAL_PATH = "/search/v1/local";

    private static final int MAX_RESULTS = 5;

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    /** {@code mapx}·{@code mapy} 를 경위도로 — 10⁷ 로 나눈다. */
    private static final int COORDINATE_SHIFT = 7;

    /** {@code stop.lat}·{@code lng} 가 {@code numeric(9,6)} 이다. */
    private static final int COORDINATE_SCALE = 6;

    private final WebClient webClient;

    private final String baseUrl;

    private final String keyId;

    private final String key;

    public NaverPlaceSearchClient(WebClient webClient,
            @Value("${app.place-search.naver.base-url}") String baseUrl,
            @Value("${app.place-search.naver.key-id:}") String keyId,
            @Value("${app.place-search.naver.key:}") String key) {
        this.webClient = webClient;
        this.baseUrl = baseUrl;
        this.keyId = keyId;
        this.key = key;
    }

    @Override
    @CircuitBreaker(name = RESILIENCE_INSTANCE, fallbackMethod = "unavailable")
    public List<FoundPlace> search(String query) {
        LocalResponse response;
        try {
            response = webClient.get()
                    .uri(localUri(query))
                    .header("x-ncp-apigw-api-key-id", keyId)
                    .header("x-ncp-apigw-api-key", key)
                    // 공급자가 JSON 을 text/plain 으로 보낸다(실측) — 그대로 두면 JSON 해석기가 받지 않는다.
                    .exchangeToMono(answer -> answer.statusCode().is2xxSuccessful()
                            ? answer.mutate().headers(headers -> headers.setContentType(MediaType.APPLICATION_JSON))
                                    .build().bodyToMono(LocalResponse.class)
                            : answer.createError())
                    .block(TIMEOUT);
        } catch (RuntimeException e) {
            throw new GeocodingUnavailableException("장소 검색 호출 실패: " + query, e);
        }
        if (response == null || response.items() == null) {
            return List.of();
        }
        return response.items().stream()
                .filter(item -> item.mapx() != null && item.mapy() != null)
                .map(item -> new FoundPlace(withoutTags(item.title()),
                        new GeocodedPoint(degrees(item.mapy()), degrees(item.mapx()), item.addressOrJibun())))
                .toList();
    }

    /**
     * 서킷이 열려 막힌 호출도 호출자가 이미 처리하는 포트 예외 하나로 모은다. {@code private} 이 아닌 것은
     * Resilience4j 가 리플렉션으로 찾기 때문이다.
     */
    List<FoundPlace> unavailable(String query, Throwable cause) {
        if (cause instanceof GeocodingUnavailableException unavailable) {
            throw unavailable;
        }
        throw new GeocodingUnavailableException("장소 검색 호출 실패: " + query, cause);
    }

    private URI localUri(String query) {
        return UriComponentsBuilder.fromUriString(baseUrl)
                .path(LOCAL_PATH)
                .queryParam("query", query)
                .queryParam("display", MAX_RESULTS)
                .build()
                .encode()
                .toUri();
    }

    /** 검색어 강조 태그({@code <b>…</b>})를 걷어낸다 — 표시명에 그대로 들어가면 목록에 태그가 보인다. */
    private static String withoutTags(String title) {
        return title == null ? "" : title.replaceAll("<[^>]+>", "").trim();
    }

    private static BigDecimal degrees(String scaled) {
        return new BigDecimal(scaled).movePointLeft(COORDINATE_SHIFT).setScale(COORDINATE_SCALE, RoundingMode.HALF_UP);
    }

    /** 네이버 지역 검색 응답 바디 — 후보 목록만 옮겨 담는다. */
    record LocalResponse(List<LocalItem> items) {
    }

    /** 이름을 못박는다 — 앱의 JSON 설정이 snake_case 라 그대로 두면 {@code roadAddress} 가 {@code road_address} 로 읽혀 빈다. */
    record LocalItem(String title, String address, @JsonProperty("roadAddress") String roadAddress, String mapx,
            String mapy) {

        /** 도로명이 비는 장소가 있어 지번으로 물러난다. */
        String addressOrJibun() {
            return roadAddress == null || roadAddress.isBlank() ? address : roadAddress;
        }
    }
}
