package src.backend.schedule.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

import src.backend.routing.domain.RouteDirection;
import src.backend.schedule.entity.LocationChangeDecision;
import src.backend.schedule.entity.LocationChangeRequest;

/**
 * 위치 변경 신청 결과. 승인 대기 상태가 없으므로 응답 시점에 이미 판정({@code decision})이 확정돼 있다 —
 * 화면은 {@code reason} 을 그대로 보여주면 된다.
 */
public record LocationChangeRequestResponse(
        Long id,
        Long tenantId,
        Long studentId,
        Long requestedBy,
        RouteDirection direction,
        LocalDate targetDate,
        Double newLat,
        Double newLng,
        String newAddress,
        LocationChangeDecision decision,
        Double deltaDistanceM,
        Double deltaDurationS,
        Long appliedPlanId,
        String reason,
        LocalDateTime createdAt) {

    public static LocationChangeRequestResponse from(LocationChangeRequest e) {
        return new LocationChangeRequestResponse(
                e.getId(), e.getTenantId(), e.getStudentId(), e.getRequestedBy(),
                e.getDirection(), e.getTargetDate(),
                e.getNewLat(), e.getNewLng(), e.getNewAddress(),
                e.getDecision(), e.getDeltaDistanceM(), e.getDeltaDurationS(),
                e.getAppliedPlanId(), e.getReason(), e.getCreatedAt());
    }
}
