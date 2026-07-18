package src.backend.global.event;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/**
 * 모든 도메인 이벤트(RideCompletedEvent, StudentBoardedEvent 등)가 구현하는 공통 계약.
 * Kafka 발행·소비에 필요한 최소 메타데이터만 강제하고, 실제 페이로드는 각 이벤트 record 가 갖는다.
 */
public interface DomainEvent {

    UUID eventId();

    Instant occurredAt();

    Long tenantId();

    /** dedupKey 조립용 — 알림 소비자가 "대상일자"로 쓰는 시스템 기본 시간대 기준 날짜. */
    default LocalDate occurredDate() {
        return occurredAt().atZone(ZoneId.systemDefault()).toLocalDate();
    }
}
