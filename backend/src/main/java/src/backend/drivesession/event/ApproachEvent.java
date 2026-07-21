package src.backend.drivesession.event;

import java.time.Instant;
import java.util.UUID;

import src.backend.global.event.DomainEvent;

/**
 * 정류장 도착 임박(5분 전) — {@code DriveSessionCommandService.checkApproachAndNoShow()}가
 * 진행 중 등원 세션의 정류장별 ETA를 훑다가 임계값에 걸리면 발행한다.
 * 알림 모듈이 APPROACH 알림을 만드는 데 필요한 최소 정보만 담는다.
 */
public record ApproachEvent(
        UUID eventId,
        Instant occurredAt,
        Long tenantId,
        Long studentId,
        String studentName,
        Long busId,
        Long routePlanStopId
) implements DomainEvent {

    public static ApproachEvent of(Long tenantId, Long studentId, String studentName, Long busId, Long routePlanStopId) {
        return new ApproachEvent(UUID.randomUUID(), Instant.now(), tenantId, studentId, studentName, busId, routePlanStopId);
    }
}
