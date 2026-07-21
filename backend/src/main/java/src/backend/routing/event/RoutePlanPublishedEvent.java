package src.backend.routing.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import src.backend.global.event.DomainEvent;
import src.backend.routing.domain.RouteDirection;
import src.backend.routing.entity.RoutePlan;

/**
 * 노선 계획 배포(운행 시작) — {@code RoutingCommandService.publish()}(관리자 배포·F4 자동배정 확정 공용)가
 * {@code RoutePlan(PUBLISHED)} 전이 직후 발행한다(F3). 알림 파이프라인이 studentId 기준이라 배포 계획의
 * 첫 정차 학생을 대표로 삼아, 그 학생의 담당 기사(=배포 대상 버스 기사)에게 push가 도달하게 한다
 * ({@code PushTargetResolver.resolveDriverUserId} 재사용, 신규 인프라 불필요).
 */
public record RoutePlanPublishedEvent(
        UUID eventId,
        Instant occurredAt,
        Long tenantId,
        Long busId,
        RouteDirection direction,
        Long routePlanId,
        int version,
        LocalDate serviceDate,
        Long representativeStudentId,
        String representativeStudentName
) implements DomainEvent {

    public static RoutePlanPublishedEvent of(RoutePlan plan, Long representativeStudentId,
                                             String representativeStudentName) {
        return new RoutePlanPublishedEvent(
                UUID.randomUUID(),
                Instant.now(),
                plan.getTenantId(),
                plan.getBusId(),
                plan.getDirection(),
                plan.getId(),
                plan.getVersion(),
                plan.getServiceDate(),
                representativeStudentId,
                representativeStudentName);
    }
}
