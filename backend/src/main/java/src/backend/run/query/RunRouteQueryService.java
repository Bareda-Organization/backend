package src.backend.run.query;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.global.common.enums.ChangeType;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.manager.access.ManagerRunAccess;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.entity.RunStop;
import src.backend.routing.entity.Waypoint;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.routing.repository.WaypointRepository;
import src.backend.run.dto.RunRouteResponse;
import src.backend.run.dto.RunRouteResponse.RouteStop;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.student.entity.Stop;
import src.backend.student.repository.StopRepository;

/**
 * 매니저 앱의 실시간 노선 조회(API_SPEC §4.3, RUN-03·M-08·M-09, Ruling 205, Phase 9 목표 8).
 *
 * <p>{@code current_stop}·{@code next_stop} 은 저장된 포인터가 아니라 {@link RunStop#getArrivedAt()}
 * 에서 <b>매번 계산</b>한다 — 도착 기록 서비스가 이 태스크 범위 밖이라 지금은 항상 비어 있지만, 그
 * 서비스가 생기면 이 계산이 그대로 맞아떨어지도록 미리 그 값을 근거로 짠다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RunRouteQueryService {

    private final ManagerRunAccess managerRunAccess;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RunStopRepository runStopRepository;

    private final RunRiderRepository runRiderRepository;

    private final StopRepository stopRepository;

    private final WaypointRepository waypointRepository;

    public RunRouteResponse route(AuthUser requester, Long runId) {
        ManagerRunAccess.RunAssignment assigned = managerRunAccess.requireAssignedRun(requester, runId);
        Run run = assigned.run();
        if (run.getStatus() == RunStatus.IDLE) {
            throw new BusinessException(ErrorCode.RUN_NOT_CONFIRMED);
        }
        Long currentVersionId = confirmedRouteRepository.findById(run.getId())
                .map(ConfirmedRoute::getCurrentVersionId)
                .orElse(null);
        if (currentVersionId == null) {
            return new RunRouteResponse(List.of(), null, null, null);
        }
        return buildFromVersion(requester, run, currentVersionId);
    }

    /**
     * 정차 목록·현재/다음 정류장·건너뜀 안내 조립 — {@link #route} 와 관계자용 §5.19
     * {@code StaffRunRouteQueryService}(F1 S3 목표 11)가 공유한다. 두 호출자가 여기 오기 전까지의
     * 경로(배치 확인 vs 학원 범위 확인)와 버전 미확정 시 처리(빈 200 vs 409)만 서로 다르고, 버전이
     * 정해진 뒤의 조립 자체는 같은 판정을 두 벌 만들지 않도록 여기 하나로 묶는다(과업 지시서 판단
     * 근거). 패키지 전용이라 같은 {@code run.query} 패키지의 관계자용 서비스에서만 호출된다.
     */
    RunRouteResponse buildFromVersion(AuthUser requester, Run run, Long currentVersionId) {
        List<RunStop> runStops = runStopRepository.findAllByRouteVersionIdAndAcademyIdOrderBySeq(currentVersionId,
                requester.academyId());
        Map<Long, Stop> stopsById = stopRepository
                .findAllByAcademyIdAndIdIn(requester.academyId(),
                        runStops.stream().map(RunStop::getStopId).filter(id -> id != null).toList())
                .stream()
                .collect(Collectors.toMap(Stop::getId, stop -> stop));
        Map<Long, Waypoint> waypointsById = waypointRepository.findAllAppliedByRunIdAndAcademyId(run.getId(),
                requester.academyId()).stream().collect(Collectors.toMap(Waypoint::getId, waypoint -> waypoint));
        Map<Long, Long> studentCountsByStopId = studentCountsByStopId(requester, run);

        List<RouteStop> stops = runStops.stream()
                .map(runStop -> toRouteStop(runStop, stopsById.get(runStop.getStopId()),
                        waypointsById.get(runStop.getWaypointId()), studentCountsByStopId))
                .toList();

        RunStop currentRunStop = runStops.stream()
                .filter(stop -> stop.getArrivedAt() != null)
                .max(Comparator.comparingInt(RunStop::getSeq))
                .orElse(null);
        RunStop nextRunStop = runStops.stream()
                .filter(stop -> stop.getArrivedAt() == null && stop.getChange() != ChangeType.SKIPPED)
                .min(Comparator.comparingInt(RunStop::getSeq))
                .orElse(null);
        int afterSeq = currentRunStop == null ? -1 : currentRunStop.getSeq();
        String skippedNotice = runStops.stream()
                .filter(stop -> stop.getChange() == ChangeType.SKIPPED && stop.getSeq() > afterSeq)
                .min(Comparator.comparingInt(RunStop::getSeq))
                .map(RunStop::getSkipNotice)
                .orElse(null);

        RouteStop currentStop = currentRunStop == null ? null
                : toRouteStop(currentRunStop, stopsById.get(currentRunStop.getStopId()),
                        waypointsById.get(currentRunStop.getWaypointId()), studentCountsByStopId);
        RouteStop nextStop = nextRunStop == null ? null
                : toRouteStop(nextRunStop, stopsById.get(nextRunStop.getStopId()),
                        waypointsById.get(nextRunStop.getWaypointId()), studentCountsByStopId);

        return new RunRouteResponse(stops, currentStop, nextStop, skippedNotice);
    }

    /** 승하차지별 예상 탑승 인원 — {@code absent} 는 오늘 자체가 등원 대상이 아니라 뺀다(로스터와 같은 근거). */
    private Map<Long, Long> studentCountsByStopId(AuthUser requester, Run run) {
        List<RunRider> riders = runRiderRepository.findAllByRunIdAndAcademyId(run.getId(), requester.academyId());
        return riders.stream()
                .filter(rider -> rider.getStatus() != RiderStatus.ABSENT)
                .collect(Collectors.groupingBy(RunRider::getStopId, Collectors.counting()));
    }

    private RouteStop toRouteStop(RunStop runStop, Stop stop, Waypoint waypoint, Map<Long, Long> studentCounts) {
        String change = runStop.getChange() == null ? null : runStop.getChange().name().toLowerCase(java.util.Locale.ROOT);
        if (stop != null) {
            long studentCount = studentCounts.getOrDefault(stop.getId(), 0L);
            return new RouteStop(stop.getId(), runStop.getSeq(), stop.getName(), stop.getAddress(), stop.getLat(),
                    stop.getLng(), change, studentCount);
        }
        if (waypoint != null) {
            return new RouteStop(null, runStop.getSeq(), waypoint.getLabel(), waypoint.getAddress(),
                    waypoint.getLat(), waypoint.getLng(), change, 0L);
        }
        return new RouteStop(null, runStop.getSeq(), null, null, null, null, change, 0L);
    }
}
