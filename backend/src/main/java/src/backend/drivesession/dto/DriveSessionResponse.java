package src.backend.drivesession.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

import src.backend.drivesession.entity.DriveSession;
import src.backend.drivesession.entity.DriveSessionStatus;
import src.backend.routing.domain.RouteDirection;

public record DriveSessionResponse(
        Long id,
        Long tenantId,
        Long busId,
        Long driverId,
        RouteDirection direction,
        LocalDate serviceDate,
        Long routePlanId,
        DriveSessionStatus status,
        LocalDateTime startedAt,
        LocalDateTime endedAt) {

    public static DriveSessionResponse from(DriveSession s) {
        return new DriveSessionResponse(
                s.getId(), s.getTenantId(), s.getBusId(), s.getDriverId(), s.getDirection(),
                s.getServiceDate(), s.getRoutePlanId(), s.getStatus(), s.getStartedAt(), s.getEndedAt());
    }
}
