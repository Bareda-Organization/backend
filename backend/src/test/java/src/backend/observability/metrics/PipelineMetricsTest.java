package src.backend.observability.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import src.backend.location.dto.LocationOrigin;
import src.backend.notification.domain.NotificationType;

class PipelineMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PipelineMetrics metrics = new PipelineMetrics(registry);

    @Test
    void kafkaConsumeFailed_countsPerConsumer() {
        metrics.kafkaConsumeFailed("location-push");
        metrics.kafkaConsumeFailed("location-push");
        metrics.kafkaConsumeFailed("routing-replan");

        assertThat(registry.counter("schoolbus.kafka.consume.failures", "consumer", "location-push").count())
                .isEqualTo(2.0d);
        assertThat(registry.counter("schoolbus.kafka.consume.failures", "consumer", "routing-replan").count())
                .isEqualTo(1.0d);
    }

    /** origin 태그로 실단말과 시뮬레이터를 구분한다 — 데모에서 버스가 움직이는 이유를 판별하는 근거다. */
    @Test
    void busLocationReported_separatesOrigin() {
        metrics.busLocationReported(LocationOrigin.GPS);
        metrics.busLocationReported(LocationOrigin.MOCK);
        metrics.busLocationReported(LocationOrigin.MOCK);

        assertThat(registry.counter("schoolbus.bus.location.reports", "origin", "GPS").count()).isEqualTo(1.0d);
        assertThat(registry.counter("schoolbus.bus.location.reports", "origin", "MOCK").count()).isEqualTo(2.0d);
    }

    @Test
    void notificationSent_countsPerType() {
        metrics.notificationSent(NotificationType.SOS);
        assertThat(registry.counter("schoolbus.notifications.sent", "type", "SOS").count()).isEqualTo(1.0d);
    }

    /**
     * 식별자 태그 미유입 확인(음성 대조). 버스 id 로 태그를 붙이면 시계열이 버스 수만큼 갈라지고
     * 개인 식별 가능성도 생긴다 — 설계 §4.2 의 경계다.
     */
    @Test
    void busLocationCounter_hasNoIdentifierTag() {
        metrics.busLocationReported(LocationOrigin.GPS);

        assertThat(registry.get("schoolbus.bus.location.reports").counter().getId().getTags())
                .noneMatch(tag -> tag.getKey().toLowerCase().contains("bus")
                        || tag.getKey().toLowerCase().contains("student"));
    }
}
