package src.backend.monitoring.query;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.bus.entity.Bus;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.ChangeType;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.monitoring.dto.AdminDashboardResponse;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.request.repository.RunPendingCount;
import src.backend.routing.entity.RunStop;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.run.repository.RunRepository;

/**
 * 대시보드의 오늘 회차 보드(API_SPEC §6.18 {@code today_runs[]} · {@code attention.delayed_runs[]}) — 두 목록이 같은 "오늘 미취소 회차" 와 같은 정차
 * 항목에서 나오므로 한 번에 읽는다. 지연 회차는 §6.15 {@code delayed_runs} 와 같은 정의다(지연 알림 1건 이상 · 아직 {@code finished} 아님).
 */
@Component
@RequiredArgsConstructor
class AdminDashboardTodayRuns {

    private final RunRepository runRepository;

    private final BusRepository busRepository;

    private final RunOrderedStopsLoader runOrderedStopsLoader;

    private final ChangeRequestRepository changeRequestRepository;

    private final AssignmentRepository assignmentRepository;

    /** 오늘 회차 목록과 그중 지연 회차 목록(둘 다 출발 순). */
    record Board(List<AdminDashboardResponse.TodayRun> todayRuns, List<AdminDashboardResponse.DelayedRun> delayedRuns) {
    }

    Board read(DashboardScope scope, LocalDate today) {
        List<Run> runs = runRepository.findAllByAcademyIdInAndServiceDateAndCanceledAtIsNullOrderByDepartTimeAsc(
                scope.academyIds(), today);
        if (runs.isEmpty()) {
            return new Board(List.of(), List.of());
        }
        List<Long> runIds = runs.stream().map(Run::getId).toList();
        Map<Long, Bus> buses = busRepository.findAllByAcademyIdInAndIdIn(scope.academyIds(), runs.stream().map(Run::getBusId).distinct().toList()).stream()
                .collect(Collectors.toMap(Bus::getId, bus -> bus));
        Map<Long, List<RunStop>> stopsByRun = stopsOf(runs);
        Map<Long, Long> pendingByRun = changeRequestRepository.countPendingByRun(scope.academyIds(), runIds).stream()
                .collect(Collectors.toMap(RunPendingCount::getRunId, RunPendingCount::getTotal));
        Set<Long> driverAssigned = new HashSet<>(assignmentRepository.findDriverAssignedRunIds(scope.academyIds(), runIds));
        Set<Long> delayedIds = runRepository.findDelayedToday(scope.academyIds(), today).stream().map(Run::getId)
                .collect(Collectors.toSet());

        List<AdminDashboardResponse.TodayRun> todayRuns = runs.stream()
                .map(run -> todayRunOf(run, scope, buses.get(run.getBusId()), stopsByRun.getOrDefault(run.getId(), List.of()),
                        pendingByRun.getOrDefault(run.getId(), 0L), driverAssigned.contains(run.getId())))
                .toList();
        List<AdminDashboardResponse.DelayedRun> delayedRuns = runs.stream().filter(run -> delayedIds.contains(run.getId()))
                .map(run -> new AdminDashboardResponse.DelayedRun(run.getId(), scope.academyNames().get(run.getAcademyId()),
                        buses.get(run.getBusId()).getBusNo(), lower(run.getDirection().name()),
                        RunDelayCalculator.minutesOf(run, stopsByRun.getOrDefault(run.getId(), List.of()))))
                .toList();
        return new Board(todayRuns, delayedRuns);
    }

    /** 정차 항목은 확정 뒤 회차만 있고, 학원마다 한 번씩(쿼리 2건) 읽는다 — 로더가 학원 단위로 좁혀 읽는다. */
    private Map<Long, List<RunStop>> stopsOf(List<Run> runs) {
        Map<Long, List<RunStop>> stopsByRun = new HashMap<>();
        runs.stream().filter(run -> run.getStatus() != RunStatus.IDLE).collect(Collectors.groupingBy(Run::getAcademyId))
                .forEach((academyId, academyRuns) -> stopsByRun.putAll(runOrderedStopsLoader.load(academyId, academyRuns)));
        return stopsByRun;
    }

    private AdminDashboardResponse.TodayRun todayRunOf(Run run, DashboardScope scope, Bus bus, List<RunStop> stops,
            long pendingChangeCount, boolean driverAssigned) {
        boolean confirmed = run.getStatus() != RunStatus.IDLE;
        // 진행 칸은 승하차지만 센다 — 경유 지점·도착지(승하차지 마스터를 가리키지 않는 항목)와 건너뛴 정차는 뺀다(§5.18 진행도와 같다)
        List<RunStop> boardingStops = stops.stream()
                .filter(stop -> stop.getStopId() != null && stop.getChange() != ChangeType.SKIPPED).toList();
        return new AdminDashboardResponse.TodayRun(run.getId(), run.getAcademyId(),
                scope.academyNames().get(run.getAcademyId()), bus.getBusNo(), lower(run.getDirection().name()),
                run.getDepartTime(), run.getEstDurationMin() == null ? null
                        : run.getDepartTime().plusMinutes(run.getEstDurationMin()),
                lower(run.getStatus().name()), run.getStartedAt(), run.getFinishedAt(),
                run.getStatus() == RunStatus.MOVING ? RunDelayCalculator.minutesOf(run, stops) : null,
                confirmed ? (Integer) (int) boardingStops.stream().filter(stop -> stop.getArrivedAt() != null).count() : null,
                confirmed ? (Integer) boardingStops.size() : null, pendingChangeCount, driverAssigned);
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
