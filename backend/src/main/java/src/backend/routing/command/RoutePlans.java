package src.backend.routing.command;

import java.util.List;

import src.backend.global.request.ApiValues;
import src.backend.routing.dto.RouteRegisterRequest;
import src.backend.routing.dto.RouteUpdateRequest;
import src.backend.routing.entity.Route;
import src.backend.routing.entity.RoutePlan;

/**
 * 편성 요청에서 편성 계획·정차 순서를 만드는 변환 모음(API_SPEC §5.9).
 */
final class RoutePlans {

    private RoutePlans() {
    }

    /** {@code stop_ids} 를 주지 않은 편성은 정차지 없이 시작한다 — 칸을 먼저 잡는 조작이 실재한다. */
    static List<Long> orderOf(List<Long> stopIds) {
        return stopIds == null ? List.of() : stopIds;
    }

    /** 편성 요청의 계획. */
    static RoutePlan planOf(RouteRegisterRequest request) {
        return new RoutePlan(request.busId(), ApiValues.weekday(request.weekday()),
                ApiValues.direction(request.direction()), request.name(), request.active());
    }

    /** 수정 요청의 계획 — 주지 않은 항목은 비어 있다. */
    static RoutePlan planOf(RouteUpdateRequest request) {
        return new RoutePlan(request.busId(), ApiValues.weekday(request.weekday()),
                ApiValues.direction(request.direction()), request.name(), request.active());
    }

    /** 선검사가 볼 조합 — 요청이 준 값이 우선이고 주지 않은 항목은 지금 편성의 값이다. */
    static RoutePlan merged(Route route, RoutePlan plan) {
        return new RoutePlan(
                plan.busId() == null ? route.getBusId() : plan.busId(),
                plan.weekday() == null ? route.getWeekday() : plan.weekday(),
                plan.direction() == null ? route.getDirection() : plan.direction(),
                null, null);
    }
}
