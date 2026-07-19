package src.backend.routing.infrastructure.spec;

import java.util.List;

import src.backend.routing.domain.LatLng;

/**
 * 실도로 경로(directions) 조회 포트 — 구현체 교체 가능(OSRM 기본/Naver 유료).
 * {@code routing.provider} 설정으로 활성 구현체를 고른다({@link src.backend.location.source.LocationSource}와 달리
 * 여러 개를 동시에 tick 하는 게 아니라 단일 활성 빈을 주입받는 구조라 {@code @ConditionalOnProperty}로 선택한다.
 */
public interface MapRouteClient {

    /** waypoints 순서 그대로 실도로 경로를 조회한다(최소 2개 이상). */
    RouteResult route(List<LatLng> orderedWaypoints);
}
