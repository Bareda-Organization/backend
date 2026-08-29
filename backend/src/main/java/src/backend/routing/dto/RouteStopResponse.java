package src.backend.routing.dto;

import java.math.BigDecimal;

import src.backend.routing.entity.RouteStop;
import src.backend.student.entity.Stop;

/**
 * 고정 노선의 정차 한 자리(API_SPEC §5.9) — 상세·최적화 응답이 쓴다.
 *
 * <p>좌표를 함께 싣는 이유는 관계자 웹이 편성을 <b>지도 위에</b> 그리기 때문이다. 주소 원문은 싣지
 * 않는다 — 승하차지 주소는 L3 개인정보 축(FEATURE_SPEC §6.3)이고, 편성 화면이 답해야 하는 질문은
 * "어느 자리를 어느 차례로 도는가" 라 이름과 좌표로 충분하다.
 */
public record RouteStopResponse(Long stopId, int seq, String name, BigDecimal lat, BigDecimal lng) {

    public static RouteStopResponse of(RouteStop routeStop, Stop stop) {
        return new RouteStopResponse(routeStop.getStopId(), routeStop.getSeq(), stop.getName(),
                stop.getLat(), stop.getLng());
    }
}
