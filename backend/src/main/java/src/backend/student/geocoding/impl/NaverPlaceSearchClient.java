package src.backend.student.geocoding.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import src.backend.student.geocoding.spec.GeocodedPoint;
import src.backend.student.geocoding.spec.GeocodingUnavailableException;
import src.backend.student.geocoding.spec.PlaceSearchClient;

/**
 * NAVER API HUB 지역 검색(2026-09-23 실측) — 개발자센터 검색 API 가 2026-07-31 부터 신규 발급을 멈추고
 * 네이버 클라우드 플랫폼 HUB 로 옮겨졌다.
 *
 * <p>⚠ 헤더는 NCP 방식({@code x-ncp-apigw-api-key-id}·{@code x-ncp-apigw-api-key})이다 — 옛
 * {@code X-Naver-Client-Id} 로 보내면 {@code 401 Authentication Failed}(실측). 좌표는 {@code mapx}·
 * {@code mapy} 에 <b>경위도 × 10⁷ 정수</b>로 온다. 한 번에 최대 5건이다.
 *
 * <p>재시도·서킷을 두지 않는다 — 자동완성의 보조 후보라 실패하면 주소 후보만 보이면 되고, 입력마다
 * 부르는 호출에 재시도를 얹으면 느린 순간이 길어질 뿐이다. 대신 상한 시간을 짧게 둔다.
 */
@Component
@ConditionalOnProperty(name = "app.place-search.provider", havingValue = "naver", matchIfMissing = true)
public class NaverPlaceSearchClient implements PlaceSearchClient {

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
    public List<FoundPlace> search(String query) {
        LocalResponse response;
        try {
            response = webClient.get()
                    .uri(localUri(query))
                    .header("x-ncp-apigw-api-key-id", keyId)
                    .header("x-ncp-apigw-api-key", key)
                    .retrieve()
                    .bodyToMono(LocalResponse.class)
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

    record LocalResponse(List<LocalItem> items) {
    }

    record LocalItem(String title, String address, String roadAddress, String mapx, String mapy) {

        /** 도로명이 비는 장소가 있어 지번으로 물러난다. */
        String addressOrJibun() {
            return roadAddress == null || roadAddress.isBlank() ? address : roadAddress;
        }
    }
}
