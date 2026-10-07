package src.backend.routing.assembly;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.bus.entity.Bus;
import src.backend.bus.repository.BusRepository;
import src.backend.routing.dto.StopManageResponse;
import src.backend.routing.dto.StopManageResponse.RouteRef;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.routing.repository.RouteStopRepository.StopRoute;
import src.backend.student.entity.Stop;
import src.backend.student.repository.WeeklyAddressRepository;
import src.backend.student.repository.WeeklyAddressRepository.StopRiderCount;

/**
 * 승하차지 여럿을 관리 항목으로 조립한다(API_SPEC §5.9 · Ruling 849) — 목록과 수정이 같은 형태를 돌려주므로 조립을 한 곳에 둔다.
 *
 * <p><b>승하차지 수와 무관하게 질의 3건이다</b> — 편성·호차·학생 수를 각각 한 번에 읽는다. 항목마다 읽으면 한 쪽(최대 100건)이
 * 질의 300건이 된다(횡단 규칙 4).
 */
@Component
@RequiredArgsConstructor
public class StopManageAssembler {

    private final RouteStopRepository routeStopRepository;

    private final BusRepository busRepository;

    private final WeeklyAddressRepository weeklyAddressRepository;

    /** 같은 학원의 승하차지들을 받은 차례 그대로 항목으로 바꾼다. */
    public List<StopManageResponse> assemble(Long academyId, List<Stop> stops) {
        if (stops.isEmpty()) {
            return List.of();
        }
        List<Long> stopIds = stops.stream().map(Stop::getId).toList();
        List<StopRoute> held = routeStopRepository.findRoutesByStopIdsAndAcademyId(stopIds, academyId);
        Map<Long, String> busNos = busNosOf(academyId, held);
        Map<Long, List<RouteRef>> routesByStop = held.stream().collect(Collectors.groupingBy(StopRoute::getStopId,
                Collectors.mapping(route -> RouteRef.of(route.getRouteId(), busNos.get(route.getBusId()),
                        route.getWeekday(), route.getDirection(), route.getActive()), Collectors.toList())));
        Map<Long, Long> studentCounts = weeklyAddressRepository.countStudentsByStopIds(academyId, stopIds).stream()
                .collect(Collectors.toMap(StopRiderCount::getStopId, StopRiderCount::getTotal));
        return stops.stream()
                .map(stop -> StopManageResponse.of(stop, routesByStop.getOrDefault(stop.getId(), List.of()),
                        studentCounts.getOrDefault(stop.getId(), 0L)))
                .toList();
    }

    private Map<Long, String> busNosOf(Long academyId, List<StopRoute> held) {
        if (held.isEmpty()) {
            return Map.of();
        }
        return busRepository.findAllByAcademyIdAndIdIn(academyId, held.stream().map(StopRoute::getBusId).distinct().toList())
                .stream().collect(Collectors.toMap(Bus::getId, Bus::getBusNo, (first, second) -> first));
    }
}
