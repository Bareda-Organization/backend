package src.backend.observability.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

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
}
