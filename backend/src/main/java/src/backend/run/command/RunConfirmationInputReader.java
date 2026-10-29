package src.backend.run.command;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.global.common.enums.Weekday;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.location.GeoPoint;
import src.backend.routing.entity.Route;
import src.backend.routing.entity.RouteStop;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.domain.RunRouteEndpoints;
import src.backend.run.entity.Run;
import src.backend.student.entity.Stop;
import src.backend.student.repository.StopRepository;

/**
 * 확정 배치({@link RunConfirmationService})가 회차 하나의 노선 계산 입력을 만들 때 읽는 학원 · 고정 노선 · 승하차지 — 읽기뿐이라 트랜잭션 밖에서 부른다.
 */
@Component
@RequiredArgsConstructor
class RunConfirmationInputReader {

    private final AcademyRepository academyRepository;

    private final RouteRepository routeRepository;

    private final RouteStopRepository routeStopRepository;

    private final StopRepository stopRepository;

    /** 학원을 읽는다 — 좌표 미등록 학원은 이 회차만 실패시킨다(Ruling 190: 다른 기준점으로 대체하지 않는다). */
    Academy academyWithCoordinates(Run run) {
        Academy academy = academyRepository.findById(run.getAcademyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));
        if (!academy.hasCoordinates()) {
            throw new BusinessException(ErrorCode.ACADEMY_COORDINATES_MISSING);
        }
        return academy;
    }

    /** 회차에 대응하는 고정 노선의 정차 순서 — 노선이 없거나 정차지가 비어 있으면 노선 미편성이다. */
    List<RouteStop> routeStopsOf(Run run, Weekday weekday) {
        Route route = routeRepository
                .findByAcademyIdAndBusIdAndWeekdayAndDirection(run.getAcademyId(), run.getBusId(), weekday,
                        run.getDirection())
                .orElseThrow(() -> new BusinessException(ErrorCode.ROUTE_NOT_CONFIGURED_FOR_RUN));
        List<RouteStop> routeStops = routeStopRepository.findAllOrderedByRouteIdAndAcademyId(route.getId(),
                run.getAcademyId());
        if (routeStops.isEmpty()) {
            throw new BusinessException(ErrorCode.ROUTE_NOT_CONFIGURED_FOR_RUN);
        }
        return routeStops;
    }

    /** 방향에 따라 학원과 첫 · 끝 승하차지로 출발 · 도착 좌표를 정한다. */
    RunRouteEndpoints.Endpoints endpointsOf(Run run, Academy academy, List<RouteStop> routeStops) {
        List<Long> stopIds = routeStops.stream().map(RouteStop::getStopId).toList();
        Map<Long, Stop> stopsById = stopRepository.findAllByIdInAndAcademyId(stopIds, run.getAcademyId()).stream()
                .collect(Collectors.toMap(Stop::getId, stop -> stop));
        Stop firstStop = stopsById.get(routeStops.get(0).getStopId());
        Stop lastStop = stopsById.get(routeStops.get(routeStops.size() - 1).getStopId());
        if (firstStop == null || lastStop == null) {
            // 정차 순서가 가리키는 승하차지가 학원 밖(또는 삭제됨) — findAllByIdInAndAcademyId 가
            // 빠뜨린 것과 같은 신호라 노선 미편성과 같은 오류로 답한다.
            throw new BusinessException(ErrorCode.ROUTE_NOT_CONFIGURED_FOR_RUN);
        }
        return RunRouteEndpoints.of(run.getDirection(), new GeoPoint(academy.getLat(), academy.getLng()),
                new GeoPoint(firstStop.getLat(), firstStop.getLng()), new GeoPoint(lastStop.getLat(), lastStop.getLng()));
    }
}
