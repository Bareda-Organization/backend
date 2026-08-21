package src.backend.global.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import src.backend.auth.dto.LoginRequest;
import src.backend.auth.dto.TokenResponse;
import src.backend.global.response.ApiResponse;

/**
 * 지표 노출 경로 테스트. Prometheus 가 스크레이프하는 유일한 통로이므로,
 * 이 엔드포인트가 막히면 대시보드 4장이 전부 빈 값이 된다.
 *
 * MockMvc(웹 슬라이스)가 아니라 실서버(RANDOM_PORT)로 기동한다 — MockMvc 는
 * {@code ServerHttpObservationFilter} 를 거치지 않을 수 있어 http_server_requests 지표가
 * 아예 기록되지 않고, 그러면 설정이 정상이어도 테스트가 실패하거나 반대로 원인을 설정 탓으로 오진한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PrometheusEndpointTest {

    @LocalServerPort
    private int port;

    private final TestRestTemplate restTemplate = new TestRestTemplate();

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    @Test
    void prometheusEndpoint_isExposed() {
        ResponseEntity<String> response = restTemplate.getForEntity(baseUrl() + "/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** 백분위수는 기본 비활성이다 — 히스토그램 설정이 빠지면 p95·p99 패널이 전부 빈 값이 된다. */
    @Test
    void httpServerRequests_hasHistogramBuckets() {
        restTemplate.getForEntity(baseUrl() + "/actuator/health", String.class);

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(java.util.List.of(MediaType.ALL));
        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl() + "/actuator/prometheus", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getBody()).contains("http_server_requests_seconds_bucket");
    }

    /**
     * URI 태그가 경로 패턴으로 묶이는지 확인한다.
     * id 별로 시계열이 갈라지면(`/api/buses/1`, `/api/buses/2` …) 시계열 수가 무한히 늘어
     * Prometheus 메모리가 급증한다. 패턴(`{id}`)으로 묶여야 한다.
     *
     * 시드 관리자로 로그인해 실제 토큰으로 호출한다 — 미인증 요청은 Security 필터에서 끊겨
     * 핸들러 매핑 전에 반환되므로 uri 라벨이 아예 기록되지 않고, 그러면 raw id 미포함 검증만으로는
     * "패턴으로 잘 묶였다"와 "요청 자체가 관측되지 않았다"를 구분하지 못한다(둘 다 raw id 는 없다).
     * 그래서 부정 검증(raw id 미포함) 앞에 긍정 검증(패턴 태그 포함) 을 둔다 — 후자가 실패하면
     * 요청이 관측되지 않았다는 뜻이라 공허한 통과가 구조적으로 막힌다.
     * 두 버스(3호차·1호차)는 로그인한 관리자와 같은 학원(한빛) 소속이라 둘 다 200 을 받는다.
     */
    @Test
    void uriTag_isPathPattern_notRawId() {
        String accessToken = loginAsAdmin();
        HttpHeaders authHeaders = new HttpHeaders();
        authHeaders.setBearerAuth(accessToken);
        HttpEntity<Void> authRequest = new HttpEntity<>(authHeaders);

        ResponseEntity<String> bus1Response =
                restTemplate.exchange(baseUrl() + "/api/buses/1", HttpMethod.GET, authRequest, String.class);
        ResponseEntity<String> bus2Response =
                restTemplate.exchange(baseUrl() + "/api/buses/2", HttpMethod.GET, authRequest, String.class);
        assertThat(bus1Response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bus2Response.getStatusCode()).isEqualTo(HttpStatus.OK);

        HttpHeaders acceptHeaders = new HttpHeaders();
        acceptHeaders.setAccept(java.util.List.of(MediaType.ALL));
        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl() + "/actuator/prometheus", HttpMethod.GET, new HttpEntity<>(acceptHeaders), String.class);
        String body = response.getBody();

        assertThat(body).contains("uri=\"/api/buses/{id}\"");
        assertThat(body).doesNotContain("uri=\"/api/buses/1\"");
        assertThat(body).doesNotContain("uri=\"/api/buses/2\"");
    }

    /** 시드 관리자(admin@school.com / password)로 로그인해 accessToken 을 발급받는다. */
    private String loginAsAdmin() {
        HttpEntity<LoginRequest> loginRequest = new HttpEntity<>(new LoginRequest("admin@school.com", "password"));

        ResponseEntity<ApiResponse<TokenResponse>> response = restTemplate.exchange(
                baseUrl() + "/api/auth/login",
                HttpMethod.POST,
                loginRequest,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().data().accessToken();
    }
}
