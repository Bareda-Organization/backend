package src.backend.global.event;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;

/**
 * DB↔Kafka 이중쓰기 정합성 패턴. 도메인 서비스는 트랜잭션 안에서
 * {@code ApplicationEventPublisher.publishEvent(domainEvent)} 만 호출하면 되고(Kafka를 모른다),
 * 이 리스너가 커밋 이후에만 실행되어 Kafka로 릴레이한다.
 * 롤백되면 이 메서드 자체가 호출되지 않아 "DB는 롤백, Kafka엔 발행됨" 불일치를 막는다.
 * 반대로 커밋 직후·릴레이 이전 프로세스가 죽는 경우는 여전히 유실 가능성으로 남는 트레이드오프 —
 * 완전한 exactly-once가 필요해지면 Outbox 패턴으로 승격한다.
 */
@Component
@RequiredArgsConstructor
public class TransactionalDomainEventRelay {

    private final DomainEventPublisher domainEventPublisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void relay(DomainEvent event) {
        domainEventPublisher.publish(event);
    }
}
