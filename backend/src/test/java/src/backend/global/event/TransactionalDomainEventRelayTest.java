package src.backend.global.event;

import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * AFTER_COMMIT 이후 relay() 가 호출되면 DomainEventPublisher.publish() 로 그대로 위임되는지만 검증한다.
 * 트랜잭션 경계(커밋/롤백) 자체는 Spring 트랜잭션 매니저의 책임이라 여기서는 테스트하지 않는다.
 */
class TransactionalDomainEventRelayTest {

    private record SampleEvent(UUID eventId, Instant occurredAt, Long tenantId) implements DomainEvent {
    }

    @Test
    void relay_forwards_event_to_publisher() {
        DomainEventPublisher publisher = Mockito.mock(DomainEventPublisher.class);
        TransactionalDomainEventRelay relay = new TransactionalDomainEventRelay(publisher);
        DomainEvent event = new SampleEvent(UUID.randomUUID(), Instant.now(), 1L);

        relay.relay(event);

        verify(publisher).publish(event);
    }
}
