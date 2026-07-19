package src.backend.routing.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import src.backend.routing.domain.RouteDirection;
import src.backend.routing.domain.RoutePlanStatus;
import src.backend.routing.entity.RoutePlan;
import src.backend.routing.entity.RoutePlanStop;

public record RoutePlanResponse(
        Long id,
        Long tenantId,
        Long busId,
        RouteDirection direction,
        RoutePlanStatus status,
        int version,
        LocalDate serviceDate,
        double totalDistanceM,
        double totalDurationS,
        String polyline,
        List<StopEntry> stops,
        LocalDateTime createdAt) {

    public record StopEntry(int seq, Long studentId, double lat, double lng, long etaSeconds) {

        public static StopEntry from(RoutePlanStop s) {
            return new StopEntry(s.getSeq(), s.getStudentId(), s.getLat(), s.getLng(), s.getEtaSeconds());
        }
    }

    public static RoutePlanResponse from(RoutePlan plan) {
        return new RoutePlanResponse(
                plan.getId(), plan.getTenantId(), plan.getBusId(), plan.getDirection(), plan.getStatus(),
                plan.getVersion(), plan.getServiceDate(), plan.getTotalDistanceM(), plan.getTotalDurationS(),
                plan.getPolyline(), plan.getStops().stream().map(StopEntry::from).toList(), plan.getCreatedAt());
    }
}
