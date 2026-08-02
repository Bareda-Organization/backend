package src.backend.schedule.event;

import java.time.Instant;
import java.util.UUID;

import src.backend.global.event.DomainEvent;
import src.backend.schedule.entity.LocationChangeDecision;
import src.backend.schedule.entity.LocationChangeRequest;

/**
 * 등하원 위치 변경 요청의 자동 판정 결과 — {@code LocationChangeCommandService.create()} 가
 * 요청 행 저장 후 발행한다. 알림 모듈이 {@code NotificationType.LOCATION_CHANGE_RESULT} 로
 * 보호자에게 전달한다(토픽은 클래스명에서 유도돼 {@code location-change-result}).
 */
public record LocationChangeResultEvent(
        UUID eventId,
        Instant occurredAt,
        Long tenantId,
        Long studentId,
        String studentName,
        Long locationChangeRequestId,
        LocationChangeDecision decision,
        String reason
) implements DomainEvent {

    public static LocationChangeResultEvent of(LocationChangeRequest request, String studentName) {
        return new LocationChangeResultEvent(
                UUID.randomUUID(),
                Instant.now(),
                request.getTenantId(),
                request.getStudentId(),
                studentName,
                request.getId(),
                request.getDecision(),
                request.getReason());
    }
}
