package src.backend.run.query;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.global.common.enums.Weekday;
import src.backend.global.location.GeoPoint;
import src.backend.routing.engine.spec.OrderedStop;
import src.backend.routing.entity.Route;
import src.backend.routing.entity.RouteStop;
import src.backend.routing.entity.RouteVersionSource;
import src.backend.routing.map.spec.CallerPolicy;
import src.backend.routing.pipeline.ComputationPolicy;
import src.backend.routing.pipeline.DailyRoster;
import src.backend.routing.pipeline.RouteComputation;
import src.backend.routing.pipeline.RouteComputationInput;
import src.backend.routing.pipeline.RouteComputationPipeline;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.domain.RunRouteEndpoints;
import src.backend.run.dto.RunRouteResponse;
import src.backend.run.dto.StaffRunRouteResponse;
import src.backend.run.dto.StaffRunRouteResponse.Ack;
import src.backend.run.entity.Run;
import src.backend.student.access.DailyStopLookup;
import src.backend.student.entity.Stop;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentDailyStop;

/**
 * 확정 전(idle) 회차의 예정 경로를 고정 노선에서 계산해 §5.19 응답으로 조립한다.
 * 트랜잭션 없이 {@link StaffRunRouteQueryService} 가 부른다 — 계산이 외부 지도 API 를 부르므로 DB 커넥션을
 * 쥔 채 기다리지 않도록 이 클래스에도 트랜잭션을 걸지 않는다(BR-046).
 */
@Component
@RequiredArgsConstructor
class StaffPlannedRouteAssembler {

    /** 예정 경로 계산 — 관계자가 화면에서 대기 중이므로 서킷 개방 시 즉시 오류로 끝낸다(ON_DEMAND). */
    private static final Duration PLANNED_ROUTE_MAP_TIMEOUT = Duration.ofSeconds(5);

    private final AcademyRepository academyRepository;

    private final RouteRepository routeRepository;

    private final RouteStopRepository routeStopRepository;

    private final StopRepository stopRepository;

    private final DailyStopLookup dailyStopLookup;

    private final RouteComputationPipeline routeComputationPipeline;

    private final Clock clock;

    /**
     * 확정 전(idle) 회차의 <b>예정 경로</b>(Ruling 321) — 고정 노선(§7 규칙 12 교체 축과
     * 무관하게 {@code route}·{@code route_stop} 이 이미 갖고 있는 데이터)과 오늘 자 요일별 주소만으로
     * 계산한다. 강제 추가·버스 간 이동·승인된 경유지 재배치 같은 당일 변경(RTE-06·07, P-06)은 반영하지
     * 않는다 — 확정 배치가 그 순간의 명단으로 다시 계산하므로 이 값은 처음부터 "예정"일 뿐이고, 그
     * 근사를 위해 {@code confirmOne} 의 roster 조립 전부를 그대로 옮겨 오는 비용을 지지 않는다(과업
     * 지시서 판단 근거 — 예정 노선은 확정본과 다를 수 있음을 이미 계약으로 명시했다).
     *
     * <p>캐시하지 않는다 — 관리자만 쓰고 조회가 잦지 않다(사용자 확정, Ruling 321 정정).
     *
     * @return 고정 노선을 못 찾거나 학원에 좌표가 없으면 {@code Optional.empty()} — 호출부가 이 경우도
     *         {@code RUN_NOT_CONFIRMED} 로 묶어 "조용한 빈 값" 대신 명시적 오류로 알린다
     */
    Optional<StaffRunRouteResponse> plannedRouteOf(Run run) {
        Academy academy = academyRepository.findById(run.getAcademyId()).orElse(null);
        if (academy == null || !academy.hasCoordinates()) {
            return Optional.empty();
        }
        Weekday weekday = Weekday.of(run.getServiceDate());
        List<RouteStop> routeStops = fixedRouteStopsOf(run, weekday);
        if (routeStops.isEmpty()) {
            return Optional.empty();
        }

        List<Long> stopIds = routeStops.stream().map(RouteStop::getStopId).toList();
        Map<Long, Stop> stopsById = stopsByIdOf(run, stopIds);
        Stop firstStop = stopsById.get(routeStops.get(0).getStopId());
        Stop lastStop = stopsById.get(routeStops.get(routeStops.size() - 1).getStopId());
        if (firstStop == null || lastStop == null) {
            return Optional.empty();
        }

        RunRouteEndpoints.Endpoints endpoints = endpointsOf(run, academy, firstStop, lastStop);
        return Optional.of(computePlannedRoute(run, weekday, endpoints, stopIds, stopsById));
    }

    /** 회차의 버스·요일·방향에 맞는 고정 노선의 정차 목록 — 노선이 없으면 빈 목록. */
    private List<RouteStop> fixedRouteStopsOf(Run run, Weekday weekday) {
        Route route = routeRepository
                .findByAcademyIdAndBusIdAndWeekdayAndDirection(run.getAcademyId(), run.getBusId(), weekday,
                        run.getDirection())
                .orElse(null);
        if (route == null) {
            return List.of();
        }
        return routeStopRepository.findAllOrderedByRouteIdAndAcademyId(route.getId(), run.getAcademyId());
    }

    private Map<Long, Stop> stopsByIdOf(Run run, List<Long> stopIds) {
        return stopRepository.findAllByIdInAndAcademyId(stopIds, run.getAcademyId()).stream()
                .collect(Collectors.toMap(Stop::getId, stop -> stop));
    }

    private RunRouteEndpoints.Endpoints endpointsOf(Run run, Academy academy, Stop firstStop, Stop lastStop) {
        GeoPoint academyPoint = new GeoPoint(academy.getLat(), academy.getLng());
        // Ruling 190(confirmOne 과 같은 규칙) — RunRouteEndpoints 로 통합(BR-101).
        return RunRouteEndpoints.of(run.getDirection(), academyPoint,
                new GeoPoint(firstStop.getLat(), firstStop.getLng()), new GeoPoint(lastStop.getLat(), lastStop.getLng()));
    }

    /** 오늘 자 요일별 주소로 경로를 계산해 응답으로 조립한다. */
    private StaffRunRouteResponse computePlannedRoute(Run run, Weekday weekday, RunRouteEndpoints.Endpoints endpoints,
            List<Long> stopIds, Map<Long, Stop> stopsById) {
        List<StudentDailyStop> dailyStops = dailyStopLookup.findDailyStopsByStopIds(run.getAcademyId(),
                stopIds, weekday, run.getDirection(), run.getServiceDate().atStartOfDay(clock.getZone()).toOffsetDateTime());
        RouteComputation computation = routeComputationPipeline
                .compute(computationInputOf(run, weekday, endpoints, dailyStops));
        return responseOf(computation, stopsById, dailyStops);
    }

    private RouteComputationInput computationInputOf(Run run, Weekday weekday, RunRouteEndpoints.Endpoints endpoints,
            List<StudentDailyStop> dailyStops) {
        List<Long> studentIds = dailyStops.stream().map(StudentDailyStop::getStudentId).distinct().toList();
        DailyRoster roster = new DailyRoster(run.getAcademyId(), weekday, run.getDirection(), studentIds, Map.of());
        ComputationPolicy policy = new ComputationPolicy(PLANNED_ROUTE_MAP_TIMEOUT, CallerPolicy.ON_DEMAND,
                RouteVersionSource.APPROVAL, false);
        return new RouteComputationInput(roster, endpoints.origin(), endpoints.destination(), List.of(),
                run.getDepartTime(), policy);
    }

    private StaffRunRouteResponse responseOf(RouteComputation computation, Map<Long, Stop> stopsById,
            List<StudentDailyStop> dailyStops) {
        Map<Long, Long> studentStops = dailyStops.stream()
                .collect(Collectors.toMap(StudentDailyStop::getStudentId, StudentDailyStop::getStopId,
                        (first, duplicate) -> first));
        Map<Long, Long> studentCountsByStopId = studentStops.values().stream()
                .collect(Collectors.groupingBy(stopId -> stopId, Collectors.counting()));
        List<RunRouteResponse.RouteStop> stops = computation.stops().stream()
                .map(stop -> toRouteStop(stop, stopsById.get(stop.stopId()), studentCountsByStopId))
                .toList();
        return new StaffRunRouteResponse(stops, null, null, null, 0, null,
                new Ack(false, false), computation.roadPath(), computation.snapshot().fallbackUsed(), false);
    }

    private RunRouteResponse.RouteStop toRouteStop(OrderedStop stop, Stop stopEntity,
            Map<Long, Long> studentCounts) {
        if (stopEntity == null) {
            return new RunRouteResponse.RouteStop(null, stop.seq(), null, null, null, null, null, 0L, false, false,
                    null);
        }
        long studentCount = studentCounts.getOrDefault(stopEntity.getId(), 0L);
        return new RunRouteResponse.RouteStop(stopEntity.getId(), stop.seq(), stopEntity.getName(),
                stopEntity.getAddress(), stopEntity.getLat(), stopEntity.getLng(), null, studentCount, false, false,
                null);
    }
}
