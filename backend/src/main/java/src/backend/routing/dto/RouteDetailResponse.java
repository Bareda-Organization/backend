package src.backend.routing.dto;

import java.util.List;

import src.backend.routing.entity.Route;

/**
 * 고정 노선 <b>상세</b> 응답(API_SPEC §5.9) — 상세 조회 · 편성 · 수정 · 최적화가 함께 쓴다.
 *
 * <p>편성·수정·최적화가 목록 응답이 아니라 이것을 돌려주는 이유는 §1.9 다 — 변경 후 자원 상태를
 * 그대로 반환해야 클라이언트가 다시 조회하지 않는다. 특히 최적화는 <b>바뀐 순서가 응답의 본체</b>라
 * 정차 목록을 빼면 무엇이 달라졌는지 알 수단이 부재하다.
 *
 * @param stops {@code seq} 차례로 정렬돼 있다 — 순서가 이 편성의 내용 자체이므로 정렬은 계약의 일부다
 */
public record RouteDetailResponse(Long id, Long busId, String busNo, String weekday, String direction,
        String name, boolean active, List<RouteStopResponse> stops) {

    public static RouteDetailResponse of(Route route, String busNo, List<RouteStopResponse> stops) {
        RouteResponse summary = RouteResponse.of(route, busNo);
        return new RouteDetailResponse(summary.id(), summary.busId(), summary.busNo(), summary.weekday(),
                summary.direction(), summary.name(), summary.active(), stops);
    }
}
