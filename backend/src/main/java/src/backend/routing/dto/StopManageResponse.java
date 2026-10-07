package src.backend.routing.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

import src.backend.student.entity.Stop;

/**
 * 승하차지 관리 항목(API_SPEC §5.9 · Ruling 849) — 목록과 수정이 같은 형태를 돌려준다(§1.9).
 *
 * @param routes 이 승하차지를 정차지로 담은 편성 — 비활성 편성도 싣고, 없으면 빈 배열
 * @param studentCount 요일별 주소가 이 승하차지로 매칭된 재원 학생 수(한 학생은 요일·방향이 여럿이어도 1명)
 */
public record StopManageResponse(Long stopId, String name, String address, BigDecimal lat, BigDecimal lng,
        List<RouteRef> routes, long studentCount) {

    /** 승하차지와 따로 읽은 편성·학생 수를 합쳐 항목 하나를 만든다. */
    public static StopManageResponse of(Stop stop, List<RouteRef> routes, long studentCount) {
        return new StopManageResponse(stop.getId(), stop.getName(), stop.getAddress(), stop.getLat(), stop.getLng(),
                routes, studentCount);
    }

    /** 승하차지를 담은 편성 하나 — 호차 번호를 함께 실어 화면이 차량을 다시 조회하지 않게 한다. */
    public record RouteRef(Long routeId, String busNo, String weekday, String direction, boolean active) {

        /** 요일·방향은 응답에서 소문자 문자열이다({@code RouteResponse} 와 같다). */
        public static RouteRef of(Long routeId, String busNo, Enum<?> weekday, Enum<?> direction, boolean active) {
            return new RouteRef(routeId, busNo, weekday.name().toLowerCase(Locale.ROOT),
                    direction.name().toLowerCase(Locale.ROOT), active);
        }
    }
}
