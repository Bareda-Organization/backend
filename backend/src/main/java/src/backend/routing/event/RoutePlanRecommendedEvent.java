package src.backend.routing.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import src.backend.global.event.DomainEvent;
import src.backend.routing.domain.RouteDirection;
import src.backend.routing.entity.RoutePlan;

/**
 * 결석/일정변경 승인으로 트리거된 국소 replan 결과 — {@code RoutingCommandService.replanForStudent()}가
 * {@code RoutePlan(RECOMMENDED)} 저장 후 발행한다(Phase 6e). 알림 모듈이
 * {@code NotificationType.ROUTE_RECOMMENDED}로 관리자에게 전달한다.
 */
public record RoutePlanRecommendedEvent(
        UUID eventId,
        Instant occurredAt,
        Long tenantId,
        Long busId,
        RouteDirection direction,
        Long routePlanId,
        int version,
        LocalDate serviceDate,
        Long triggerStudentId,
        String triggerStudentName
) implements DomainEvent {

    public static RoutePlanRecommendedEvent of(RoutePlan plan, Long triggerStudentId, String triggerStudentName) {
        return new RoutePlanRecommendedEvent(
                UUID.randomUUID(),
                Instant.now(),
                plan.getTenantId(),
                plan.getBusId(),
                plan.getDirection(),
                plan.getId(),
                plan.getVersion(),
                plan.getServiceDate(),
                triggerStudentId,
                triggerStudentName);
    }
}
