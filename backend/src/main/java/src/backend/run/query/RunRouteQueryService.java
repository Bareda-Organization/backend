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
        return buildFromVersion(run, currentVersionId);
    }

    /**
     * 정차 목록·현재/다음 정류장·건너뜀 안내 조립 — {@link #route} 와 관계자용 §5.19
     * {@code StaffRunRouteQueryService}(F1 S3 목표 11)가 공유한다. 두 호출자가 여기 오기 전까지의
     * 경로(배치 확인 vs 학원 범위 확인)와 버전 미확정 시 처리(빈 200 vs 409)만 서로 다르고, 버전이
     * 정해진 뒤의 조립 자체는 같은 판정을 두 벌 만들지 않도록 여기 하나로 묶는다(과업 지시서 판단
     * 근거). 패키지 전용이라 같은 {@code run.query} 패키지의 관계자용 서비스에서만 호출된다.
     *
     * <p>학원 조건은 요청자가 아니라 <b>회차의 학원</b>으로 건다(BR-014) — 메인 관리자는 {@code academyId}
     * 가 {@code null} 이라 요청자 기준이면 네 조회가 전부 0행이 된다. 두 호출자 모두 여기 오기 전에 접근
     * 판정(배치 확인 · {@code AcademyScope#assertAccessible})을 끝냈다.
     */
    RunRouteResponse buildFromVersion(Run run, Long currentVersionId) {
        Long academyId = run.getAcademyId();
        List<RunStop> runStops = runStopRepository.findAllByRouteVersionIdAndAcademyIdOrderBySeq(currentVersionId,
                academyId);
        Map<Long, Stop> stopsById = stopRepository
                .findAllByAcademyIdAndIdIn(academyId,
                        runStops.stream().map(RunStop::getStopId).filter(id -> id != null).toList())
                .stream()
                .collect(Collectors.toMap(Stop::getId, stop -> stop));
        Map<Long, Waypoint> waypointsById = waypointRepository.findAllAppliedByRunIdAndAcademyId(run.getId(),
                academyId).stream().collect(Collectors.toMap(Waypoint::getId, waypoint -> waypoint));
        Map<Long, Long> studentCountsByStopId = studentCountsByStopId(run);

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
                .filter(stop -> hasResolvedTarget(stop, stopsById, waypointsById))
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

    /**
     * {@code next_stop} 후보가 실제 좌표를 낼 수 있는가(API_SPEC §1.13, §4.3 목표) —
     * {@link Stop}·{@link Waypoint} 어느 쪽으로도 안 풀리면(배포 후 제거된 경유 지점이 대표 사례,
     * {@link WaypointRepository#findAllAppliedByRunIdAndAcademyId} 가 제거분을 map 에서 뺀다) 그 행은
     * {@link #toRouteStop} 에서 좌표가 전부 {@code null} 인 항목이 된다. {@code next_stop} 은 "외부
     * 내비게이션 앱 콜백용"이라 좌표 없는 항목을 내보내면 그 용도 자체가 깨지므로, 이미 지나친
     * ({@code SKIPPED}) 항목과 같은 방식으로 건너뛰어 진짜 다음 정차지(좌표 있는 항목)를 고른다.
     * {@code stops[]} 배열 자체(전체 목록)는 그대로 좌표 없는 항목을 보존한다 — 그쪽은 상위 항목에만
     * {@code ●} 가 있고 내부 필드는 별도 등급(API_SPEC §1.13)이라 이 필터를 적용할 근거가 없다.
     */
    private boolean hasResolvedTarget(RunStop runStop, Map<Long, Stop> stopsById, Map<Long, Waypoint> waypointsById) {
        return stopsById.get(runStop.getStopId()) != null || waypointsById.get(runStop.getWaypointId()) != null;
    }

    /** 승하차지별 예상 탑승 인원 — {@code absent} 는 오늘 자체가 등원 대상이 아니라 뺀다(로스터와 같은 근거). */
    private Map<Long, Long> studentCountsByStopId(Run run) {
        List<RunRider> riders = runRiderRepository.findAllByRunIdAndAcademyId(run.getId(), run.getAcademyId());
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
