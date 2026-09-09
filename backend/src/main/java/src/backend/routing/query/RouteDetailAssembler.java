package src.backend.routing.query;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.bus.entity.Bus;
import src.backend.bus.repository.BusRepository;
import src.backend.routing.dto.RouteDetailResponse;
import src.backend.routing.dto.RouteStopResponse;
import src.backend.routing.entity.Route;
import src.backend.routing.entity.RouteStop;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.student.entity.Stop;
import src.backend.student.repository.StopRepository;

/**
 * 편성 1건을 상세 응답으로 조립한다(API_SPEC §5.9) — 조회·편성·수정·최적화 넷이 같은 형태를 돌려주므로
 * 조립을 한 곳에 둔다(§1.9 는 변경 후 자원 상태를 그대로 반환하라고 정한다).
 *
 * <p>조회 서비스가 아니라 별도 컴포넌트인 이유는 <b>쓰기 서비스도 이것을 부르기</b> 때문이다 —
 * 조회 서비스에 두면 쓰기가 조회를 의존하게 되어 두 흐름의 트랜잭션 속성({@code readOnly})이 섞인다.
 */
@Component
@RequiredArgsConstructor
public class RouteDetailAssembler {

    private final RouteStopRepository routeStopRepository;

    private final StopRepository stopRepository;

    private final BusRepository busRepository;

    /** 편성과 그 정차 순서를 {@code seq} 차례로 담은 상세 응답. */
    public RouteDetailResponse assemble(Route route) {
        List<RouteStop> ordered = routeStopRepository.findAllOrderedByRouteIdAndAcademyId(
                route.getId(), route.getAcademyId());
        return RouteDetailResponse.of(route, busNoOf(route), stopResponsesOf(route, ordered));
    }

    /**
     * 정차 순번마다 승하차지 이름·좌표를 붙인다 — 좌표를 한 번에 읽어 오는 것이 요점이다.
     *
     * <p>{@code RouteStop} 에 {@code Stop} 연관을 매핑하지 않은 이유는 그러면 정차지 수만큼 조회가
     * 붙기 때문이다(횡단 규칙 4).
     */
    private List<RouteStopResponse> stopResponsesOf(Route route, List<RouteStop> ordered) {
        if (ordered.isEmpty()) {
            return List.of();
        }
        Map<Long, Stop> stops = stopRepository.findAllByAcademyIdAndIdIn(route.getAcademyId(),
                        ordered.stream().map(RouteStop::getStopId).toList()).stream()
                .collect(Collectors.toMap(Stop::getId, Function.identity()));
        return ordered.stream().map(routeStop -> RouteStopResponse.of(routeStop, stops.get(routeStop.getStopId())))
                .toList();
    }

    /**
     * 편성이 물고 있는 차량의 호차 — 차량은 편성 등록 시 학원으로 좁혀 확인했으므로 여기서 비는 일은
     * 부재하나, 그럼에도 {@code findByIdAndAcademyId} 로 읽어 학원 조건을 이 경로에서도 유지한다.
     */
    private String busNoOf(Route route) {
        return busRepository.findByIdAndAcademyId(route.getBusId(), route.getAcademyId())
                .map(Bus::getBusNo)
                .orElse(null);
    }
}
