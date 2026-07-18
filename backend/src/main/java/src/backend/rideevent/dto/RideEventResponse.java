package src.backend.rideevent.dto;

import java.time.LocalDateTime;

import src.backend.rideevent.entity.RideEvent;
import src.backend.rideevent.entity.RideSource;
import src.backend.rideevent.entity.RideType;

/**
 * 승하차 기록 응답. 정정 이력 필드(correctedBy/correctedAt/originalRef)까지 그대로 노출해
 * 관리자가 원본↔정정 관계를 추적할 수 있게 한다.
 */
public record RideEventResponse(
        Long id,
        Long tenantId,
        Long studentId,
        Long busId,
        Long stopId,
        RideType type,
        LocalDateTime occurredAt,
        Double lat,
        Double lng,
        RideSource source,
        Long correctedBy,
        LocalDateTime correctedAt,
        Long originalRef) {

    public static RideEventResponse from(RideEvent e) {
        return new RideEventResponse(
                e.getId(), e.getTenantId(), e.getStudentId(), e.getBusId(), e.getStopId(),
                e.getType(), e.getOccurredAt(), e.getLat(), e.getLng(), e.getSource(),
                e.getCorrectedBy(), e.getCorrectedAt(), e.getOriginalRef());
    }
}
