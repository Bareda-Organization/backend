package src.backend.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * TECH_DECISIONS §13.1 8행이 요구하는 지표 이름이 <b>이벤트 없이도</b> {@code /actuator/prometheus}
 * 본문에 전부 노출되는지 확인한다(관측 목표 8) — 대시보드 패널은 지표가 처음 값을 받기 전에도 그려져야
 * 하고, Prometheus 는 이름이 한 번도 노출되지 않은 지표를 스크레이프 대상으로 조차 인식하지 못한다.
 *
 * <p>도메인 이벤트를 하나도 발생시키지 않는다 — 이 시험의 요점은 "값이 맞는가" 가 아니라
 * "기동만으로 이름이 있는가" 다. {@link PrometheusEndpointTest} 와 같은 이유로 MockMvc 가 아니라
 * 실서버(RANDOM_PORT)로 기동한다.
 *
 * <p>Prometheus 노출 형식은 {@code .} 을 {@code _} 로 바꾸고, counter 에는 {@code _total}, timer 에는
 * {@code _seconds_count}·{@code _seconds_sum}·{@code _seconds_max} 접미사를 붙인다 — 아래 단언은
 * 전부 그 변환된 형태를 그대로 적는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MetricsExposureTest {

    @LocalServerPort
    private int port;

    private final TestRestTemplate restTemplate = new TestRestTemplate();

    @Test
    void 관측_목표_8행_전부_이벤트_없이_노출된다() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(java.util.List.of(MediaType.ALL));
        ResponseEntity<String> response = restTemplate.exchange(
                "http://localhost:" + port + "/actuator/prometheus", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        String body = response.getBody();

        assertThat(body)
                .as("1행 확정 배치 도래→완료 지연")
                .contains("schoolbus_run_confirmation_lag_seconds_count");
        assertThat(body)
                .as("2행 미확정 회차 수")
                .contains("schoolbus_run_unconfirmed");
        assertThat(body)
                .as("3행 배치 실패·재시도")
                .contains("schoolbus_scheduler_failures_total");
        assertThat(body)
                .as("4행 지도 API 응답시간·실패율·서킷 — resilience4j.circuitbreaker.metrics.legacy.enabled 로 연결")
                .contains("resilience4j_circuitbreaker_calls_seconds_count")
                .contains("resilience4j_circuitbreaker_state")
                .contains("geocoding")
                .contains("mapRoute");
        assertThat(body)
                .as("5행 알림 발송 실패")
                .contains("schoolbus_notification_push_failures_total");
        assertThat(body)
                .as("6행 WS 연결 수")
                .contains("schoolbus_stomp_sessions");
        assertThat(body)
                .as("6행 WS 발행 지연")
                .contains("schoolbus_websocket_publish_latency_seconds_count");
        assertThat(body)
                .as("7행 ②구간 자동 거절")
                .contains("schoolbus_change_request_auto_rejected_total");
        assertThat(body)
                .as("8행 미승차 에스컬레이션")
                .contains("schoolbus_no_show_escalated_total");
    }
}
