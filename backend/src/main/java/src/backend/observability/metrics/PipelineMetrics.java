package src.backend.observability.metrics;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;

import lombok.RequiredArgsConstructor;

/**
 * 비동기 파이프라인이 살아 있는지를 계측한다 — 지금은 Kafka 소비 실패 하나뿐이다.
 * 위치 수신·알림 발송 계측은 그 대상 도메인이 재작성되는 대로 되돌아온다.
 *
 * <p>Kafka 컨슈머가 예외를 삼키고 로그만 남긴 선례가 있어(저장이 CHECK 제약 위반으로 막혔는데
 * 앱은 생존) HTTP 응답에는 드러나지 않는 실패를 여기서 잡는다.
 *
 * <p>태그는 <b>종류</b>만 붙이고 식별자는 붙이지 않는다 — 시계열이 대상 수만큼 갈라지는 것을
 * 막고, 개인 식별 정보가 지표에 섞이지 않게 한다.
 */
@Component
@RequiredArgsConstructor
public class PipelineMetrics {

    private final MeterRegistry registry;

    /** Kafka 메시지 처리 중 예외 발생. 증가가 멈추지 않으면 그 컨슈머가 계속 실패하는 중이다. */
    public void kafkaConsumeFailed(String consumer) {
        registry.counter("schoolbus.kafka.consume.failures", "consumer", consumer).increment();
    }
}
