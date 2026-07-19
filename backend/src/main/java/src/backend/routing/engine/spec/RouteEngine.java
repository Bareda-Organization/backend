package src.backend.routing.engine.spec;

import java.util.List;
import java.util.Map;

import src.backend.routing.domain.LatLng;

/**
 * 방문 순서 최적화 포트 — 알고리즘 교체 가능성이 있는 전략이라 인터페이스로 분리한다(§11.3).
 * 외부 호출 없는 순수 계산만 수행한다(실도로 거리·ETA는 {@link src.backend.routing.infrastructure.spec.MapRouteClient} 몫).
 */
public interface RouteEngine {

    /**
     * depot 기준 방문순서 최적화. 반환값은 항상 "depot에서 출발하는" 순서이며,
     * 등원/하원 방향 처리(뒤집기+depot 위치 조정)는 호출부(RoutingCommandService) 몫이다.
     */
    List<Long> optimizeOrder(LatLng depot, Map<Long, LatLng> studentPoints);
}
