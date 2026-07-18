package src.backend.global.event;

/**
 * 이벤트 발행 포트 — 구현 기술(Kafka)은 {@code global.infrastructure.KafkaEventPublisher} 에 감추고,
 * 비즈니스 계층은 이 인터페이스만 안다. 발행 기술이 바뀌어도(Kafka → Pulsar 등) 호출부는 수정하지 않는다.
 */
public interface DomainEventPublisher {

    void publish(DomainEvent event);
}
