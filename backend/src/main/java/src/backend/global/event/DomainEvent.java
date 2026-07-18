package src.backend.global.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 모든 도메인 이벤트(RideCompletedEvent, StudentBoardedEvent 등)가 구현하는 공통 계약.
 * Kafka 발행·소비에 필요한 최소 메타데이터만 강제하고, 실제 페이로드는 각 이벤트 record 가 갖는다.
 */
public interface DomainEvent {

    UUID eventId();

    Instant occurredAt();

    Long tenantId();
}
