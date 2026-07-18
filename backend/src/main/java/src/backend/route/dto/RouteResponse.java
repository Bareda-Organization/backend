package src.backend.route.dto;

import src.backend.route.entity.Route;

/**
 * 노선 요약 응답.
 * assignedCount(이 노선에 배정된 학생 수) > assignCapacity(배정 정원) 이면 초과 배정 경고(기능9).
 */
public record RouteResponse(
        Long id,
        Long tenantId,
        String name,
        int assignCapacity,
        int assignedCount,
        boolean overCapacity) {

    public static RouteResponse of(Route route, int assignedCount) {
        return new RouteResponse(
                route.getId(),
                route.getTenant().getId(),
                route.getName(),
                route.getAssignCapacity(),
                assignedCount,
                assignedCount > route.getAssignCapacity());
    }
}
