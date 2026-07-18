package src.backend.global.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import src.backend.global.event.DomainEvent;
import src.backend.global.event.DomainEventPublisher;

/**
 * DomainEventPublisher 의 Kafka 구현체. 토픽은 이벤트 클래스명에서 케밥 케이스로 유도한다
 * (예: RideCompletedEvent → ride-completed). 파티션 키는 tenantId 로 고정해 같은 학원의
 * 이벤트는 항상 같은 파티션에 쌓여 컨슈머 입장에서 발생 순서가 보장된다.
 */
@Component
public class KafkaEventPublisher implements DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publish(DomainEvent event) {
        String topic = toTopic(event.getClass());
        String key = String.valueOf(event.tenantId());
        kafkaTemplate.send(topic, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("[kafka] publish failed topic={} eventId={}", topic, event.eventId(), ex);
                    }
                });
    }

    private String toTopic(Class<?> eventClass) {
        String name = eventClass.getSimpleName().replaceFirst("Event$", "");
        return name.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase();
    }
}
