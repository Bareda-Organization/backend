package src.backend.routing.domain;

/** 위경도 좌표 — RouteEngine·MapRouteClient가 공유하는 순수 값 타입. */
public record LatLng(double lat, double lng) {
}
