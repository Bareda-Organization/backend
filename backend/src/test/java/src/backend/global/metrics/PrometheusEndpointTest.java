package src.backend.global.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

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

    // uriTag_isPathPattern_notRawId(경로 패턴 태그 묶임 검증)는 검증 대상이던 옛 버스 조회 API가
    // 삭제되며 함께 없앴다 — 새 컨트롤러가 생기는 대로(Phase 2+) 그 엔드포인트를 대상으로 되돌린다.
}
