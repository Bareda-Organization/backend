package src.backend.sos.dto;

import java.time.LocalDateTime;

import src.backend.sos.entity.SosEvent;
import src.backend.sos.entity.SosStatus;

public record SosEventResponse(
        Long id,
        Long tenantId,
        Long studentId,
        SosStatus status,
        Double lat,
        Double lng,
        LocalDateTime occurredAt,
        Long acknowledgedBy,
        LocalDateTime acknowledgedAt,
        Long resolvedBy,
        LocalDateTime resolvedAt) {

    public static SosEventResponse from(SosEvent e) {
        return new SosEventResponse(
                e.getId(), e.getTenantId(), e.getStudentId(), e.getStatus(),
                e.getLat(), e.getLng(), e.getOccurredAt(),
                e.getAcknowledgedBy(), e.getAcknowledgedAt(),
                e.getResolvedBy(), e.getResolvedAt());
    }
}
