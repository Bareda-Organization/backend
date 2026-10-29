package src.backend.run.roster;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Weekday;
import src.backend.request.entity.ChangeRequest;
import src.backend.request.entity.ChangeRequestStatus;
import src.backend.request.entity.ChangeRequestType;
import src.backend.request.repository.BoardingIntentRepository;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.routing.entity.Route;
import src.backend.routing.entity.RouteStop;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunForcedAddition;
import src.backend.run.entity.RunTransfer;
import src.backend.run.repository.RunForcedAdditionRepository;
import src.backend.run.repository.RunTransferRepository;
import src.backend.student.access.DailyStopLookup;
import src.backend.student.repository.StudentDailyStop;

/**
 * 같은 학원 회차 묶음의 예정 명단 재료({@link RosterSources})를 <b>회차 수와 무관한 조회 횟수</b>로 읽는다(DA-7) — 노선 · 노선 정차지 · 요일별 주소(방향별) · 탑승 의사 · 승인된 변경 · 강제 추가 · 이동을
 * 묶음으로 한 번씩 읽고 회차별로 가른다. {@link ProjectedRosterReader#readAll} 이 부른다.
 */
@Component
@RequiredArgsConstructor
class RosterBatchLoader {

    private final RouteRepository routeRepository;

    private final RouteStopRepository routeStopRepository;

    private final DailyStopLookup dailyStopLookup;

    private final BoardingIntentRepository boardingIntentRepository;

    private final ChangeRequestRepository changeRequestRepository;

    private final RunForcedAdditionRepository runForcedAdditionRepository;

    private final RunTransferRepository runTransferRepository;

    /** 운행일 0시를 서비스 시간대로 얻는다 — 퇴원생 제외(BR-003)의 기준 시각. */
    private final Clock clock;

    /** 회차마다의 조립 재료 — 같은 학원(다르면 {@link IllegalArgumentException}) 회차만 받는다. */
    Map<Long, RosterSources> load(List<Run> runs) {
        Long academyId = academyIdOf(runs);
        List<Long> runIds = runs.stream().map(Run::getId).toList();
        Batch batch = new Batch(dailyStopsOf(academyId, runs), excludedByRun(academyId, runIds),
                relocationsByRun(academyId, runIds), forcedByRun(academyId, runIds), outgoingByRun(academyId, runIds),
                incomingByRun(academyId, runIds));
        return runs.stream().collect(Collectors.toMap(Run::getId, batch::sourcesOf, (first, second) -> second));
    }

    private static Long academyIdOf(List<Run> runs) {
        Long academyId = runs.get(0).getAcademyId();
        if (runs.stream().anyMatch(run -> !run.getAcademyId().equals(academyId))) {
            throw new IllegalArgumentException("readAll 은 한 학원의 회차만 받는다");
        }
        return academyId;
    }

    private Map<Long, List<Long>> excludedByRun(Long academyId, List<Long> runIds) {
        return boardingIntentRepository.findRidingFalseByRunIdInAndAcademyId(runIds, academyId).stream()
                .collect(Collectors.groupingBy(BoardingIntentRepository.RunStudent::getRunId,
                        Collectors.mapping(BoardingIntentRepository.RunStudent::getStudentId, Collectors.toList())));
    }

    private Map<Long, List<ChangeRequest>> relocationsByRun(Long academyId, List<Long> runIds) {
        return changeRequestRepository.findAllByAcademyIdAndRunIdInAndTypeAndStatusOrderByRequestedAtAsc(academyId,
                runIds, ChangeRequestType.RELOCATE, ChangeRequestStatus.APPROVED).stream()
                .collect(Collectors.groupingBy(ChangeRequest::getRunId));
    }

    private Map<Long, List<RunForcedAddition>> forcedByRun(Long academyId, List<Long> runIds) {
        return runForcedAdditionRepository.findAllByRunIdInAndAcademyId(runIds, academyId).stream()
                .collect(Collectors.groupingBy(RunForcedAddition::getRunId));
    }

    private Map<Long, List<RunTransfer>> outgoingByRun(Long academyId, List<Long> runIds) {
        return runTransferRepository.findAllByFromRunIdInAndAcademyId(runIds, academyId).stream()
                .collect(Collectors.groupingBy(RunTransfer::getFromRunId));
    }

    private Map<Long, List<RunTransfer>> incomingByRun(Long academyId, List<Long> runIds) {
        return runTransferRepository.findAllByToRunIdInAndAcademyId(runIds, academyId).stream()
                .collect(Collectors.groupingBy(RunTransfer::getToRunId));
    }

    /** 버스 · 요일 · 방향 → 고정 노선 id. 노선이 없는 회차는 키가 없다. */
    private Map<RouteKey, Long> routesOf(Long academyId, List<Run> runs) {
        List<Long> busIds = runs.stream().map(Run::getBusId).distinct().toList();
        Map<RouteKey, Long> routes = new HashMap<>();
        for (Route route : routeRepository.findAllByAcademyIdAndBusIdIn(academyId, busIds)) {
            routes.put(new RouteKey(route.getBusId(), route.getWeekday(), route.getDirection()), route.getId());
        }
        return routes;
    }

    /** 쓰이는 고정 노선마다 정차지 id 를 순서대로 모은다. */
    private Map<Long, List<Long>> stopIdsByRoute(Long academyId, List<Run> runs, Map<RouteKey, Long> routeIds) {
        List<Long> usedRouteIds = runs.stream().map(run -> routeIds.get(RouteKey.of(run))).filter(Objects::nonNull)
                .distinct().toList();
        return usedRouteIds.isEmpty() ? Map.of()
                : routeStopRepository.findAllOrderedByRouteIdInAndAcademyId(usedRouteIds, academyId).stream()
                        .collect(Collectors.groupingBy(RouteStop::getRouteId, LinkedHashMap::new,
                                Collectors.mapping(RouteStop::getStopId, Collectors.toList())));
    }

    /**
     * 회차 묶음의 요일별 주소 학생 — 요일 · 방향 · 학생 퇴원 기준일이 같은 회차끼리 한 번에 읽고, 회차마다 <b>자기 노선의 정차지</b> 학생만 남긴다
     * ({@link ProjectedRosterReader#read(Run)} 가 노선 정차지 목록으로 조회를 좁히는 것과 같은 결과).
     */
    private Map<DailyKey, List<StudentDailyStop>> dailyStopsOf(Long academyId, List<Run> runs) {
        Map<RouteKey, Long> routeIds = routesOf(academyId, runs);
        Map<Long, List<Long>> stopIdsByRoute = stopIdsByRoute(academyId, runs, routeIds);
        Map<DailyKey, List<StudentDailyStop>> result = new HashMap<>();
        runs.stream().collect(Collectors.groupingBy(QueryKey::of)).forEach((query, group) -> result
                .putAll(dailyStopsOfGroup(academyId, query, group, routeIds, stopIdsByRoute)));
        return result;
    }

    /** 요일 · 방향 · 운행일이 같은 회차 묶음의 요일별 주소 학생 — 쓰는 노선의 정차지가 하나도 없으면 비어 있다. */
    private Map<DailyKey, List<StudentDailyStop>> dailyStopsOfGroup(Long academyId, QueryKey query, List<Run> group,
            Map<RouteKey, Long> routeIds, Map<Long, List<Long>> stopIdsByRoute) {
        List<Long> unionStopIds = group.stream().map(run -> routeIds.get(RouteKey.of(run))).filter(Objects::nonNull)
                .flatMap(routeId -> stopIdsByRoute.getOrDefault(routeId, List.of()).stream()).distinct().toList();
        if (unionStopIds.isEmpty()) {
            return Map.of();
        }
        List<StudentDailyStop> union = dailyStopLookup.findDailyStopsByStopIds(academyId, unionStopIds,
                query.weekday(), query.direction(), serviceDayStart(group.get(0)));
        Map<DailyKey, List<StudentDailyStop>> result = new HashMap<>();
        for (Run run : group) {
            Long routeId = routeIds.get(RouteKey.of(run));
            Set<Long> stopIds = routeId == null ? Set.of() : Set.copyOf(stopIdsByRoute.getOrDefault(routeId, List.of()));
            result.put(DailyKey.of(run), union.stream().filter(stop -> stopIds.contains(stop.getStopId())).toList());
        }
        return result;
    }

    private OffsetDateTime serviceDayStart(Run run) {
        return run.getServiceDate().atStartOfDay(clock.getZone()).toOffsetDateTime();
    }

    /** 묶음 조회 결과 — 회차 id 로 가르기 전의 재료. */
    private record Batch(Map<DailyKey, List<StudentDailyStop>> dailyStops, Map<Long, List<Long>> excluded,
            Map<Long, List<ChangeRequest>> relocations, Map<Long, List<RunForcedAddition>> forced,
            Map<Long, List<RunTransfer>> outgoing, Map<Long, List<RunTransfer>> incoming) {

        RosterSources sourcesOf(Run run) {
            return new RosterSources(run, dailyStops.getOrDefault(DailyKey.of(run), List.of()),
                    excluded.getOrDefault(run.getId(), List.of()), relocations.getOrDefault(run.getId(), List.of()),
                    forced.getOrDefault(run.getId(), List.of()), outgoing.getOrDefault(run.getId(), List.of()),
                    incoming.getOrDefault(run.getId(), List.of()));
        }
    }

    private record RouteKey(Long busId, Weekday weekday, Direction direction) {
        static RouteKey of(Run run) {
            return new RouteKey(run.getBusId(), Weekday.of(run.getServiceDate()), run.getDirection());
        }
    }

    /** 요일별 주소 조회 한 번의 단위 — 요일 · 방향 · 운행일이 같은 회차는 한 번에 읽는다. */
    private record QueryKey(Weekday weekday, Direction direction, LocalDate serviceDate) {
        static QueryKey of(Run run) {
            return new QueryKey(Weekday.of(run.getServiceDate()), run.getDirection(), run.getServiceDate());
        }
    }

    /** 회차 하나의 요일별 주소 학생을 꺼내는 키. */
    private record DailyKey(Long runId) {
        static DailyKey of(Run run) {
            return new DailyKey(run.getId());
        }
    }
}
