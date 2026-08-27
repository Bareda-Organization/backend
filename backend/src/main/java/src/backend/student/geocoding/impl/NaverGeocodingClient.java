package src.backend.student.geocoding.impl;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;

import src.backend.student.geocoding.spec.GeocodedPoint;
import src.backend.student.geocoding.spec.GeocodingClient;
import src.backend.student.geocoding.spec.GeocodingUnavailableException;

/**
 * 네이버 Geocoding 어댑터(C-18 확정 공급자) — {@code geocoding.provider} 를 지정하지 않으면 이
 * 구현이 뜬다.
 *
 * <p>{@code maps.apigw.ntruss.com} 을 쓴다. 구 호스트 {@code naveropenapi.apigw.ntruss.com} 은 이
 * 자격증명으로 {@code 401 Permission Denied}(errorCode 210) 를 낸다 — 2026-08-26 실측이며, 호스트가
 * 바뀌면 인증 실패가 "키가 틀렸다" 로 오진된다(Ruling 157).
 *
 * <p>보호는 <b>어댑터 설정</b>에 둔다(§7 규칙 11) — 타임아웃은 {@code WebClientConfig},
 * 재시도·서킷은 {@code resilience4j.*.instances.geocoding} 이다. 호출부에 두면 이 포트를 부르는
 * 자리가 늘 때마다 보호가 복제되고, 한 곳이라도 빠지면 네이버 장애가 곧 주소 저장 불가가 된다.
 */
@Component
@ConditionalOnProperty(name = "geocoding.provider", havingValue = "naver", matchIfMissing = true)
public class NaverGeocodingClient implements GeocodingClient {

    /** {@code resilience4j.*.instances} 의 키 — 재시도·서킷이 같은 이름을 공유한다. */
    public static final String RESILIENCE_INSTANCE = "geocoding";

    private static final String GEOCODE_PATH = "/map-geocode/v2/geocode";

    private static final String KEY_ID_HEADER = "x-ncp-apigw-api-key-id";

    private static final String KEY_HEADER = "x-ncp-apigw-api-key";

    private final WebClient webClient;

    private final String baseUrl;

    private final String keyId;

    private final String key;

    public NaverGeocodingClient(WebClient webClient,
            @Value("${geocoding.naver.base-url}") String baseUrl,
            @Value("${geocoding.naver.key-id:}") String keyId,
            @Value("${geocoding.naver.key:}") String key) {
        this.webClient = webClient;
        this.baseUrl = baseUrl;
        this.keyId = keyId;
        this.key = key;
    }

    /**
     * 주소를 네이버에 물어 좌표를 얻는다 — {@code meta.totalCount == 0} 은 예외가 아니라 결과 0건이다.
     *
     * <p>{@code fallbackMethod} 는 <b>서킷이 열린 동안</b>과 호출이 예외로 끝났을 때만 불린다. 결과
     * 0건은 정상 응답이라 여기로 오지 않으며, 그것이 "주소가 틀렸다" 와 "지금 못 부른다" 를 가르는
     * 지점이다.
     */
    @Override
    @CircuitBreaker(name = RESILIENCE_INSTANCE, fallbackMethod = "unavailable")
    @Retry(name = RESILIENCE_INSTANCE)
    public Optional<GeocodedPoint> geocode(String address) {
        GeocodeResponse response = webClient.get()
                .uri(geocodeUri(address))
                .header(KEY_ID_HEADER, keyId)
                .header(KEY_HEADER, key)
                .retrieve()
                .bodyToMono(GeocodeResponse.class)
                .block();
        return firstAddressOf(response);
    }

    /**
     * 공급자에 닿지 못한 전부를 포트 예외 하나로 모은다 — 호출부가 원인별로 분기하지 않게 하기
     * 위함이다.
     *
     * <p>{@code private} 이 아닌 것은 Resilience4j 가 리플렉션으로 찾기 때문이고, 시그니처가 원
     * 메서드 + {@link Throwable} 인 것도 그 규약이다.
     */
    Optional<GeocodedPoint> unavailable(String address, Throwable cause) {
        throw new GeocodingUnavailableException("지오코딩 호출 실패: " + address, cause);
    }

    /** 주소를 <b>쿼리 파라미터로</b> 붙인다 — {@code encode()} 가 없으면 한글 주소가 그대로 실려 요청이 깨진다. */
    private URI geocodeUri(String address) {
        return UriComponentsBuilder.fromUriString(baseUrl)
                .path(GEOCODE_PATH)
                .queryParam("query", address)
                .build()
                .encode()
                .toUri();
    }

    private static Optional<GeocodedPoint> firstAddressOf(GeocodeResponse response) {
        if (response == null || response.addresses() == null || response.addresses().isEmpty()) {
            return Optional.empty();
        }
        NaverAddress found = response.addresses().getFirst();
        return Optional.of(new GeocodedPoint(new BigDecimal(found.y()), new BigDecimal(found.x()),
                found.displayName()));
    }

    /** 네이버 응답의 최상위 — {@code meta.totalCount} 는 {@code addresses} 길이와 같아 별도로 읽지 않는다. */
    record GeocodeResponse(List<NaverAddress> addresses) {
    }

    /**
     * 후보 한 건 — {@code x}·{@code y} 가 <b>문자열</b>로 온다(경도·위도 순서에 주의).
     *
     * <p>{@code @JsonProperty} 를 붙이는 이유는 이 앱의 전역 네이밍 전략이 snake_case 이기
     * 때문이다({@code spring.jackson.property-naming-strategy}) — 그 전략은 우리 API 계약(§1.10)을
     * 맞추려고 켠 것이고, <b>남의 응답</b>인 네이버 필드까지 {@code road_address} 로 바꿔 읽으면
     * 값이 조용히 전부 {@code null} 이 된다.
     */
    record NaverAddress(@JsonProperty("roadAddress") String roadAddress,
            @JsonProperty("jibunAddress") String jibunAddress, String x, String y) {

        /** 도로명이 비는 지번 전용 주소가 있어 지번으로 물러난다 — 둘 다 비면 표시명이 사라진다. */
        String displayName() {
            return roadAddress == null || roadAddress.isBlank() ? jibunAddress : roadAddress;
        }
    }
}
