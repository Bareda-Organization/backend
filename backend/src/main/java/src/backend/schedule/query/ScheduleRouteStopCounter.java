package src.backend.schedule.query;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Weekday;
import src.backend.routing.entity.Route;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.routing.repository.RouteStopRepository.StopCount;
import src.backend.schedule.entity.Schedule;

/**
 * 스케줄마다 같은 차량·요일·방향 고정 노선(편성)의 정차지 수를 센다(API_SPEC §5.10 {@code route_stop_count}, Ruling 818) —
 * 편성이 없으면 {@code null}, 정차지 없는 빈 편성이면 {@code 0}. 목록·등록·수정 응답이 함께 쓰므로 조회 서비스가 아니라
 * 별도 컴포넌트로 둔다(쓰기 서비스가 조회 서비스를 의존하지 않게, {@code RouteDetailAssembler} 와 같은 근거).
 */
@Component
@RequiredArgsConstructor
public class ScheduleRouteStopCounter {

    private final RouteRepository routeRepository;

    private final RouteStopRepository routeStopRepository;

    /** 스케줄 id → 정차지 수(편성이 없으면 값이 {@code null}) — 한 번에 편성과 정차지 수를 읽어 스케줄 수에 비례해 질의가 늘지 않는다. */
    public Map<Long, Integer> countsOf(Long academyId, Collection<Schedule> schedules) {
        Map<Long, Integer> counts = new HashMap<>();
        if (schedules.isEmpty()) {
            return counts;
        }
        List<Route> routes = routeRepository.findAllByAcademyIdAndBusIdIn(academyId,
                schedules.stream().map(Schedule::getBusId).distinct().toList());
        Map<RouteSlot, Long> routeIdBySlot = routes.stream().collect(Collectors.toMap(
                route -> new RouteSlot(route.getBusId(), route.getWeekday(), route.getDirection()), Route::getId));
        Map<Long, Integer> stopCounts = routes.isEmpty() ? Map.of()
                : routeStopRepository.countByRouteIdsAndAcademyId(routes.stream().map(Route::getId).toList(), academyId)
                        .stream().collect(Collectors.toMap(StopCount::getRouteId, count -> Math.toIntExact(count.getTotal())));
        for (Schedule schedule : schedules) {
            Long routeId = routeIdBySlot.get(new RouteSlot(schedule.getBusId(), schedule.getWeekday(), schedule.getDirection()));
            counts.put(schedule.getId(), routeId == null ? null : stopCounts.getOrDefault(routeId, 0));
        }
        return counts;
    }

    /** 편성을 가르는 유일 조합(차량·요일·방향 — {@code uk_route_bus_weekday_direction}). */
    private record RouteSlot(Long busId, Weekday weekday, Direction direction) {
    }
}
