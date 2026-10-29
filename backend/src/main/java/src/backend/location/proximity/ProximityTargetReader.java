package src.backend.location.proximity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.global.location.GeoPoint;
import src.backend.location.dto.RunPositionRedisValue;
import src.backend.location.proximity.ProximityNotificationService.FailureSink;
import src.backend.location.proximity.ProximityNotificationService.Judgment;
import src.backend.location.proximity.ProximityNotificationService.RunTargets;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.entity.RunStop;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RunStopProximityRepository;
import src.backend.student.entity.Stop;
import src.backend.student.repository.StopRepository;

/**
 * 근접·출발 두 판정의 재료를 읽는 단계 — 회차 묶음의 확정 노선 · 다음 미도착 정차 항목 · 도착한 정차 항목 · 정차지 좌표를 SQL 4건으로
 * 읽고, 회차마다 어느 판정이 걸렸는지 계산한다. 호출자({@link ProximityNotificationService#readTargets})의 읽기 트랜잭션 안에서 돌며
 * 읽기만 한다.
 */
@Component
@RequiredArgsConstructor
class ProximityTargetReader {

    /** 출발 판정의 읽기 결과 — 출발로 볼 정차 항목, 또는 이번 위치로 "100m 안 관측" 을 처음 기록할 정차 항목(둘 다 없을 수 있다). */
    private record DepartureRead(RunStop departureStop, RunStop nearObservedStop) {
        static final DepartureRead NONE = new DepartureRead(null, null);
    }

    /** 판본 id 로 묶은 판정 후보 정차 항목과, 그 정차지 좌표. */
    private record Candidates(Map<Long, RunStop> nextUnarrivedByVersion, Map<Long, RunStop> arrivedNotDepartedByVersion,
            Map<Long, Stop> stopsById) {
    }

    private final ProximityJudge proximityJudge;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RunStopProximityRepository runStopProximityRepository;

    private final StopRepository stopRepository;

    /** 회차 묶음의 두 판정 재료를 읽는다 — 회차 하나의 판정 계산이 던진 예외는 {@code onFailure} 로 알리고 다른 회차는 이어서 본다. */
    Map<Long, RunTargets> targetsOf(Map<Long, RunPositionRedisValue> positions, FailureSink onFailure) {
        Map<Long, Long> versionIdByRun = currentVersionIdByRun(positions);
        if (versionIdByRun.isEmpty()) {
            return Map.of();
        }
        Candidates candidates = candidatesOf(versionIdByRun);
        Map<Long, RunTargets> targets = new HashMap<>();
        versionIdByRun.forEach((runId, versionId) -> targets.put(runId,
                targetsOfRun(runId, versionId, positions.get(runId), candidates, onFailure)));
        return targets;
    }

    private Map<Long, Long> currentVersionIdByRun(Map<Long, RunPositionRedisValue> positions) {
        Map<Long, Long> versionIdByRun = new HashMap<>();
        for (ConfirmedRoute route : confirmedRouteRepository.findAllByRunIdIn(positions.keySet())) {
            if (route.getCurrentVersionId() != null) {
                versionIdByRun.put(route.getRunId(), route.getCurrentVersionId());
            }
        }
        return versionIdByRun;
    }

    private Candidates candidatesOf(Map<Long, Long> versionIdByRun) {
        List<Long> versionIds = versionIdByRun.values().stream().distinct().toList();
        Map<Long, RunStop> nextUnarrivedByVersion = byVersion(
                runStopProximityRepository.findNextUnarrivedOfVersions(versionIds));
        Map<Long, RunStop> arrivedNotDepartedByVersion = byVersion(
                runStopProximityRepository.findFirstArrivedNotDepartedOfVersions(versionIds));
        Set<Long> stopIds = new HashSet<>();
        Stream.concat(nextUnarrivedByVersion.values().stream(), arrivedNotDepartedByVersion.values().stream())
                .forEach(runStop -> stopIds.add(runStop.getStopId()));
        Map<Long, Stop> stopsById = stopRepository.findAllByIdIn(stopIds).stream()
                .collect(Collectors.toMap(Stop::getId, Function.identity()));
        return new Candidates(nextUnarrivedByVersion, arrivedNotDepartedByVersion, stopsById);
    }

    private RunTargets targetsOfRun(Long runId, Long versionId, RunPositionRedisValue position,
            Candidates candidates, FailureSink onFailure) {
        GeoPoint bus = new GeoPoint(position.lat(), position.lng());
        RunStop approach = readIsolated(runId, Judgment.APPROACH, onFailure,
                () -> approachTarget(candidates.nextUnarrivedByVersion().get(versionId), candidates.stopsById(), bus),
                null);
        DepartureRead departure = readIsolated(runId, Judgment.DEPARTURE, onFailure,
                () -> departureTarget(candidates.arrivedNotDepartedByVersion().get(versionId),
                        candidates.stopsById(), bus, position),
                DepartureRead.NONE);
        return new RunTargets(approach, departure.departureStop(), departure.nearObservedStop());
    }

    private static Map<Long, RunStop> byVersion(List<RunStop> runStops) {
        return runStops.stream().collect(Collectors.toMap(RunStop::getRouteVersionId, Function.identity(),
                (first, duplicate) -> first));
    }

    /** 다음 미도착 정차지가 300m 안이면 그 정차 항목, 아니면 {@code null}. */
    private RunStop approachTarget(RunStop nextStop, Map<Long, Stop> stopsById, GeoPoint bus) {
        if (nextStop == null) {
            return null;
        }
        Stop stop = stopsById.get(nextStop.getStopId());
        return stop != null && proximityJudge.isWithinThreshold(bus, new GeoPoint(stop.getLat(), stop.getLng()))
                ? nextStop
                : null;
    }

    /**
     * 도착했지만 출발 처리 전인 첫 정차지의 출발 판정(Ruling 307·875). 도착 처리 뒤 그 정차지 100m 안에서 받은 위치가 아직 없으면(
     * {@code near_observed_at} 이 비었으면) 100m 밖 위치는 출발이 아니다 — 안쪽이면서 도착 처리 <b>뒤</b>({@code receivedAt} 기준)에 받은
     * 위치일 때만 "관측" 으로 기록한다. 관측이 끝내 없는 정차지는 다음 승하차지 도착·운행 종료의 강제 발송(Ruling 854)이 출발 처리한다.
     */
    private DepartureRead departureTarget(RunStop targetStop, Map<Long, Stop> stopsById, GeoPoint bus,
            RunPositionRedisValue position) {
        if (targetStop == null) {
            return DepartureRead.NONE;
        }
        Stop stop = stopsById.get(targetStop.getStopId());
        if (stop == null) {
            return DepartureRead.NONE;
        }
        boolean outside = proximityJudge.hasDeparted(bus, new GeoPoint(stop.getLat(), stop.getLng()));
        if (targetStop.getNearObservedAt() != null) {
            return outside ? new DepartureRead(targetStop, null) : DepartureRead.NONE;
        }
        boolean receivedAfterArrival = !position.receivedAt().isBefore(targetStop.getArrivedAt());
        return !outside && receivedAfterArrival ? new DepartureRead(null, targetStop) : DepartureRead.NONE;
    }

    private <T> T readIsolated(Long runId, Judgment judgment, FailureSink onFailure, Supplier<T> read, T onError) {
        try {
            return read.get();
        } catch (Exception e) {
            onFailure.accept(runId, judgment, e);
            return onError;
        }
    }
}
