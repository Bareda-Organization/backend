package src.backend.routing.dto;

import java.util.Locale;

import src.backend.routing.entity.Route;

/**
 * 고정 노선 <b>요약</b> 응답(API_SPEC §5.9) — 목록이 쓴다. 관계자 웹 전용이라 역할별로 가르지 않는다
 * (고정 노선은 학부모·매니저 앱 응답에 실리지 않는다 — 그쪽은 회차로 좁혀진 §3.10 · §4.3 이다).
 *
 * <p>정차 순서를 담지 않는 것이 목록과 상세를 가르는 축이다 — 행마다 정차지를 실으면 목록 한 번에
 * 편성 수만큼의 조회가 붙는다(API_SPEC §5.5 가 같은 이유로 목록과 상세를 갈랐다).
 *
 * <p>{@code busNo} 를 함께 싣는 이유는 목록 화면이 차량을 <b>번호로</b> 보여 주기 때문이다 — 빼면
 * 클라이언트가 행마다 차량을 다시 조회해야 한다.
 */
public record RouteResponse(Long id, Long busId, String busNo, String weekday, String direction,
        String name, boolean active) {

    public static RouteResponse of(Route route, String busNo) {
        return new RouteResponse(route.getId(), route.getBusId(), busNo,
                lower(route.getWeekday().name()), lower(route.getDirection().name()),
                route.getName(), route.isActive());
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
