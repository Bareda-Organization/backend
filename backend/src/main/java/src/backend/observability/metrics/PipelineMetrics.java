package src.backend.observability.metrics;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import src.backend.location.dto.LocationOrigin;
import src.backend.notification.domain.NotificationType;

/**
 * 비동기 파이프라인이 살아 있는지를 계측한다 — Kafka 소비, 버스 위치 수신, 알림 발송 세 가지다.
 *
 * <p>세 지점 모두 실패해도 HTTP 응답에는 드러나지 않는다. Kafka 컨슈머는 예외를 삼키고 로그만 남긴
 * 선례가 있고(알림 저장이 CHECK 제약 위반으로 막혔는데 앱은 생존), 위치 보고가 끊기면 관제 지도에서
 * 버스가 사라질 뿐 오류가 나지 않는다.
 *
 * <p>태그는 <b>종류</b>만 붙이고 버스·학생 식별자는 붙이지 않는다 — 시계열이 대상 수만큼 갈라지는 것을
 * 막고, 개인 식별 정보가 지표에 섞이지 않게 한다.
 */
@Component
public class PipelineMetrics {

    private final MeterRegistry registry;

    public PipelineMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Kafka 메시지 처리 중 예외 발생. 증가가 멈추지 않으면 그 컨슈머가 계속 실패하는 중이다. */
    public void kafkaConsumeFailed(String consumer) {
        registry.counter("schoolbus.kafka.consume.failures", "consumer", consumer).increment();
    }

    /** 버스 좌표 1건 수신. 증가가 멈추면 기사 앱 보고와 서버 시뮬레이터가 모두 정지한 것이다. */
    public void busLocationReported(LocationOrigin origin) {
        registry.counter("schoolbus.bus.location.reports", "origin", origin.name()).increment();
    }

    /** 알림 1건 발송. 증가가 멈추면 알림 파이프라인이 끊긴 것이다. */
    public void notificationSent(NotificationType type) {
        registry.counter("schoolbus.notifications.sent", "type", type.name()).increment();
    }
}
