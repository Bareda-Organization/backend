package src.backend.run.roster;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
import src.backend.run.roster.ProjectedRoster.StagedRows;
import src.backend.student.repository.StudentDailyStop;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * 확정 전(idle) 회차의 예정 명단 — 확정 배치(RTE-08)가 그 회차 {@code run_rider} 에 싣는 규칙 한 벌.
 *
 * <p>정원 판정(BUS-04)의 강제 추가(§5.7)·버스 간 이동(§5.8)도 이 규칙을 쓴다 — 경로마다 명단을 따로
 * 조립하면 한쪽이 대기 중인 이동이나 OFF 학생을 빠뜨려 정원 초과를 막지 못한다(BR-043).
 *
 * <p>규칙 — 고정 노선 정차지의 요일별 주소 학생 − ①구간 OFF + 강제 추가 − 출발 이동 + 도착 이동.
 * 이동 행은 상태로 거르지 않는다({@link RunTransfer} 자바독의 자기 치유).
 */
@Component
@RequiredArgsConstructor
public class ProjectedRosterReader {

    private final RouteRepository routeRepository;

    private final RouteStopRepository routeStopRepository;

    private final WeeklyAddressRepository weeklyAddressRepository;

    private final BoardingIntentRepository boardingIntentRepository;

    private final ChangeRequestRepository changeRequestRepository;

    private final RunForcedAdditionRepository runForcedAdditionRepository;

    private final RunTransferRepository runTransferRepository;

    /** 운행일 0시를 서비스 시간대로 얻는다 — 퇴원생 제외(BR-003)의 기준 시각. */
    private final Clock clock;

    /** 고정 노선을 찾아 읽는다 — 노선이 없으면 요일별 주소 몫은 0명이다(강제 추가·이동만 남는다). */
    public ProjectedRoster read(Run run) {
        Weekday weekday = Weekday.of(run.getServiceDate());
        List<Long> stopIds = routeRepository
                .findByAcademyIdAndBusIdAndWeekdayAndDirection(run.getAcademyId(), run.getBusId(), weekday,
                        run.getDirection())
                .map(route -> routeStopRepository.findAllOrderedByRouteIdAndAcademyId(route.getId(),
                        run.getAcademyId()).stream().map(RouteStop::getStopId).toList())
                .orElse(List.of());
        return read(run, weekday, stopIds);
    }

    /** 확정 배치처럼 고정 노선의 정차지를 이미 읽은 호출부용. */
    public ProjectedRoster read(Run run, Weekday weekday, List<Long> stopIds) {
        List<StudentDailyStop> dailyStops = stopIds.isEmpty() ? List.of()
                : weeklyAddressRepository.findDailyStopsByStopIds(run.getAcademyId(), stopIds, weekday,
                        run.getDirection(), serviceDayStart(run));
        return assemble(run, dailyStops,
                boardingIntentRepository.findStudentIdsByRunIdAndRidingFalse(run.getId()),
                changeRequestRepository.findAllByAcademyIdAndRunIdAndTypeAndStatusOrderByRequestedAtAsc(
                        run.getAcademyId(), run.getId(), ChangeRequestType.RELOCATE, ChangeRequestStatus.APPROVED),
                runForcedAdditionRepository.findAllByRunIdAndAcademyId(run.getId(), run.getAcademyId()),
                runTransferRepository.findAllByFromRunIdAndAcademyId(run.getId(), run.getAcademyId()),
                runTransferRepository.findAllByToRunIdAndAcademyId(run.getId(), run.getAcademyId()));
    }

    /**
     * 같은 학원 · 같은 운행일 회차 여러 개의 예정 명단을 <b>회차 수와 무관한 조회 횟수</b>로 읽는다(DA-7) — 노선 · 노선 정차지 · 요일별
     * 주소(방향별) · 탑승 의사 · 승인된 변경 · 강제 추가 · 이동을 회차 묶음으로 한 번씩 읽고 회차별로 가른 뒤 {@link #read} 와 같은
     * 규칙({@code assemble})으로 조립한다. 결과는 회차마다 {@link #read(Run)} 와 같다.
     */
    public Map<Long, ProjectedRoster> readAll(List<Run> runs) {
        if (runs.isEmpty()) {
            return Map.of();
        }
        Long academyId = runs.get(0).getAcademyId();
        List<Long> runIds = runs.stream().map(Run::getId).toList();
        Map<RouteKey, Long> routeIds = routesOf(academyId, runs);
        List<Long> usedRouteIds = runs.stream().map(run -> routeIds.get(RouteKey.of(run))).filter(Objects::nonNull)
                .distinct().toList();
        Map<Long, List<Long>> stopIdsByRoute = usedRouteIds.isEmpty() ? Map.of()
                : routeStopRepository.findAllOrderedByRouteIdInAndAcademyId(usedRouteIds, academyId).stream()
                        .collect(Collectors.groupingBy(RouteStop::getRouteId, LinkedHashMap::new,
                                Collectors.mapping(RouteStop::getStopId, Collectors.toList())));
        Map<DailyKey, List<StudentDailyStop>> dailyStopsByKey = dailyStopsOf(academyId, runs, routeIds, stopIdsByRoute);
        Map<Long, List<Long>> excludedByRun = boardingIntentRepository
                .findRidingFalseByRunIdInAndAcademyId(runIds, academyId).stream()
                .collect(Collectors.groupingBy(BoardingIntentRepository.RunStudent::getRunId,
                        Collectors.mapping(BoardingIntentRepository.RunStudent::getStudentId, Collectors.toList())));
        Map<Long, List<ChangeRequest>> relocationsByRun = changeRequestRepository
                .findAllByAcademyIdAndRunIdInAndTypeAndStatusOrderByRequestedAtAsc(academyId, runIds,
                        ChangeRequestType.RELOCATE, ChangeRequestStatus.APPROVED).stream()
                .collect(Collectors.groupingBy(ChangeRequest::getRunId));
        Map<Long, List<RunForcedAddition>> forcedByRun = runForcedAdditionRepository
                .findAllByRunIdInAndAcademyId(runIds, academyId).stream()
                .collect(Collectors.groupingBy(RunForcedAddition::getRunId));
        Map<Long, List<RunTransfer>> outgoingByRun = runTransferRepository
                .findAllByFromRunIdInAndAcademyId(runIds, academyId).stream()
                .collect(Collectors.groupingBy(RunTransfer::getFromRunId));
        Map<Long, List<RunTransfer>> incomingByRun = runTransferRepository
                .findAllByToRunIdInAndAcademyId(runIds, academyId).stream()
                .collect(Collectors.groupingBy(RunTransfer::getToRunId));
        Map<Long, ProjectedRoster> rosters = new HashMap<>();
        for (Run run : runs) {
            rosters.put(run.getId(), assemble(run,
                    dailyStopsByKey.getOrDefault(DailyKey.of(run), List.of()),
                    excludedByRun.getOrDefault(run.getId(), List.of()),
                    relocationsByRun.getOrDefault(run.getId(), List.of()),
                    forcedByRun.getOrDefault(run.getId(), List.of()),
                    outgoingByRun.getOrDefault(run.getId(), List.of()),
                    incomingByRun.getOrDefault(run.getId(), List.of())));
        }
        return rosters;
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

    /**
     * 회차 묶음의 요일별 주소 학생 — 요일 · 방향 · 학생 퇴원 기준일이 같은 회차끼리 한 번에 읽고, 회차마다 <b>자기 노선의 정차지</b> 학생만 남긴다
     * ({@link #read(Run)} 가 노선 정차지 목록으로 조회를 좁히는 것과 같은 결과).
     */
    private Map<DailyKey, List<StudentDailyStop>> dailyStopsOf(Long academyId, List<Run> runs,
            Map<RouteKey, Long> routeIds, Map<Long, List<Long>> stopIdsByRoute) {
        Map<DailyKey, List<StudentDailyStop>> result = new HashMap<>();
        Map<QueryKey, List<Run>> runsByQuery = runs.stream().collect(Collectors.groupingBy(QueryKey::of));
        for (Map.Entry<QueryKey, List<Run>> group : runsByQuery.entrySet()) {
            QueryKey query = group.getKey();
            List<Long> unionStopIds = group.getValue().stream()
                    .map(run -> routeIds.get(RouteKey.of(run)))
                    .filter(Objects::nonNull)
                    .flatMap(routeId -> stopIdsByRoute.getOrDefault(routeId, List.of()).stream())
                    .distinct().toList();
            if (unionStopIds.isEmpty()) {
                continue;
            }
            List<StudentDailyStop> union = weeklyAddressRepository.findDailyStopsByStopIds(academyId, unionStopIds,
                    query.weekday(), query.direction(), serviceDayStart(group.getValue().get(0)));
            for (Run run : group.getValue()) {
                Long routeId = routeIds.get(RouteKey.of(run));
                Set<Long> stopIds = routeId == null ? Set.of() : Set.copyOf(stopIdsByRoute.getOrDefault(routeId, List.of()));
                result.put(DailyKey.of(run), union.stream().filter(stop -> stopIds.contains(stop.getStopId())).toList());
            }
        }
        return result;
    }

    private OffsetDateTime serviceDayStart(Run run) {
        return run.getServiceDate().atStartOfDay(clock.getZone()).toOffsetDateTime();
    }

    private record RouteKey(Long busId, Weekday weekday, Direction direction) {
        static RouteKey of(Run run) {
            return new RouteKey(run.getBusId(), Weekday.of(run.getServiceDate()), run.getDirection());
        }
    }

    /** 요일별 주소 조회 한 번의 단위 — 요일 · 방향 · 운행일이 같은 회차는 한 번에 읽는다. */
    private record QueryKey(Weekday weekday, Direction direction, java.time.LocalDate serviceDate) {
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

    /** 읽어 둔 재료로 한 회차의 예정 명단을 조립한다 — 규칙은 클래스 자바독의 한 곳뿐이다. */
    private ProjectedRoster assemble(Run run, List<StudentDailyStop> dailyStops, List<Long> excludedIds,
            List<ChangeRequest> relocations, List<RunForcedAddition> forcedAdditions,
            List<RunTransfer> outgoingTransfers, List<RunTransfer> incomingTransfers) {
        // ①구간 탑승 의사 토글(riding=false) 학생은 노선 계산에서 빼고 absent 행으로만 남긴다 — absent 는
        // "확정 노선 산출 시점 부여" 이고 "미등원 N명"·관계자 명단(RST-03)이 그 행을 센다(FEATURE_SPEC §3.3).
        Set<Long> excludedStudentIds = new HashSet<>(excludedIds);
        Map<Long, Long> absentStops = dailyStops.stream()
                .filter(stop -> excludedStudentIds.contains(stop.getStudentId()))
                .collect(Collectors.toMap(StudentDailyStop::getStudentId, StudentDailyStop::getStopId,
                        (first, duplicate) -> first));
        // 강제 추가 병합이 뒤에서 덧붙이므로 가변 목록으로 둔다(RTE-06).
        List<Long> studentIds = new ArrayList<>(
                dailyStops.stream().map(StudentDailyStop::getStudentId).distinct()
                        .filter(studentId -> !excludedStudentIds.contains(studentId))
                        .toList());
        Map<Long, Long> studentStops = dailyStops.stream()
                .filter(stop -> !excludedStudentIds.contains(stop.getStudentId()))
                .collect(Collectors.toMap(StudentDailyStop::getStudentId, StudentDailyStop::getStopId,
                        (first, duplicate) -> first));

        // P-06 일일 변경(승인된 경유지 이동)이 요일별 주소를 이긴다 — DailyStopResolver.studentToStop 과
        // 같은 우선순위(그 클래스 자바독). studentStops 에도 같은 값을 덮어써야 fingerprint·run_rider 가
        // 실제로 계산에 쓰인 경유지와 일치한다.
        Map<Long, Long> stopOverrides = new HashMap<>(relocations.stream()
                .collect(Collectors.toMap(ChangeRequest::getStudentId, ChangeRequest::getNewStopId,
                        (first, last) -> last)));
        // 승인된 경유지 이동이 OFF 학생을 명단에 되살리지 않게 한다(BR-012).
        stopOverrides.keySet().removeAll(excludedStudentIds);

        Set<Long> weeklyStudentIds = Set.copyOf(studentIds);
        // ①구간 강제 추가(RTE-06, Ruling 197·198) — 요일별 주소에 없던 학생이라 studentIds 에도 더하고,
        // stopOverrides 에 넣어야 좌표 해석 단계(DailyStopResolver.studentToStop)가 그 정차지를 찾는다.
        for (RunForcedAddition forcedAddition : forcedAdditions) {
            if (!studentIds.contains(forcedAddition.getStudentId())) {
                studentIds.add(forcedAddition.getStudentId());
            }
            stopOverrides.put(forcedAddition.getStudentId(), forcedAddition.getStopId());
        }

        // 버스 간 이동(RTE-07, API_SPEC §5.8, Ruling 256) — 출발이면 빼고 도착이면 더한다. 출발 쪽은
        // studentStops·stopOverrides 양쪽에서 지운다 — 하나만 지우면 아래 putAll 이 되살린다.
        Map<Long, Long> removedStops = new HashMap<>();
        for (RunTransfer transfer : outgoingTransfers) {
            Long stopId = stopOverrides.getOrDefault(transfer.getStudentId(), studentStops.get(transfer.getStudentId()));
            if (stopId != null) {
                removedStops.put(transfer.getStudentId(), stopId);
            }
            studentIds.remove(transfer.getStudentId());
            studentStops.remove(transfer.getStudentId());
            stopOverrides.remove(transfer.getStudentId());
        }
        for (RunTransfer transfer : incomingTransfers) {
            if (!studentIds.contains(transfer.getStudentId())) {
                studentIds.add(transfer.getStudentId());
            }
            stopOverrides.put(transfer.getStudentId(), transfer.getStopId());
        }

        studentStops.putAll(stopOverrides);
        // 강제 추가·도착 이동으로 다시 태운 학생은 absent 가 아니다 — 한 학생에 행 하나(uk_run_rider_run_student).
        absentStops.keySet().removeAll(studentStops.keySet());
        absentStops.keySet().removeAll(removedStops.keySet());
        Set<Long> addedStudentIds = new HashSet<>(studentIds);
        addedStudentIds.removeAll(weeklyStudentIds);
        return new ProjectedRoster(studentIds, stopOverrides, studentStops, absentStops, addedStudentIds, removedStops,
                incomingTransfers, stagedRowsOf(forcedAdditions, outgoingTransfers, incomingTransfers));
    }

    /**
     * 그 회차의 강제 추가·이동 행 id — 확정 저장이 트랜잭션 안에서 다시 읽어 {@link ProjectedRoster#stagedRows()}
     * 와 다르면 계산 도중 행이 들어오거나 취소된 것이다(BR-044). 이동 대기는 취소로 지워지므로(§5.8.1) 수만
     * 비교하면 취소 1 + 새 등록 1 을 놓친다.
     */
    public StagedRows stagedRows(Run run) {
        return stagedRowsOf(runForcedAdditionRepository.findAllByRunIdAndAcademyId(run.getId(), run.getAcademyId()),
                runTransferRepository.findAllByFromRunIdAndAcademyId(run.getId(), run.getAcademyId()),
                runTransferRepository.findAllByToRunIdAndAcademyId(run.getId(), run.getAcademyId()));
    }

    private static StagedRows stagedRowsOf(List<RunForcedAddition> forcedAdditions, List<RunTransfer> outgoing,
            List<RunTransfer> incoming) {
        Set<Long> transferIds = new HashSet<>();
        outgoing.forEach(transfer -> transferIds.add(transfer.getId()));
        incoming.forEach(transfer -> transferIds.add(transfer.getId()));
        return new StagedRows(forcedAdditions.stream().map(RunForcedAddition::getId).collect(Collectors.toSet()),
                transferIds);
    }

}
