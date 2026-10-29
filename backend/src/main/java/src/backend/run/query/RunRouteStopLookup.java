package src.backend.run.query;

import java.util.Locale;
import java.util.Map;

import src.backend.academy.entity.Academy;
import src.backend.routing.entity.RunStop;
import src.backend.routing.entity.Waypoint;
import src.backend.run.dto.RunRouteResponse.RouteStop;
import src.backend.student.entity.Stop;

/**
 * 한 회차의 노선 응답을 조립하는 데 쓰는 승하차지·경유 지점·학원·예상 탑승 인원 조회 묶음 —
 * 정차 행({@link RunStop})을 응답의 {@link RouteStop} 으로 바꾼다.
 */
final class RunRouteStopLookup {

    private final Map<Long, Stop> stopsById;

    private final Map<Long, Waypoint> waypointsById;

    private final Academy academy;

    private final Map<Long, Long> studentCountsByStopId;

    RunRouteStopLookup(Map<Long, Stop> stopsById, Map<Long, Waypoint> waypointsById, Academy academy,
            Map<Long, Long> studentCountsByStopId) {
        this.stopsById = stopsById;
        this.waypointsById = waypointsById;
        this.academy = academy;
        this.studentCountsByStopId = studentCountsByStopId;
    }

    /**
     * {@code next_stop} 후보가 실제 좌표를 낼 수 있는가(API_SPEC §1.13, §4.3 목표) —
     * {@link Stop}·{@link Waypoint} 어느 쪽으로도 안 풀리면(배포 후 제거된 경유 지점이 대표 사례,
     * {@code WaypointRepository#findAllAppliedByRunIdAndAcademyId} 가 제거분을 map 에서 뺀다) 그 행은
     * {@link #toRouteStop} 에서 좌표가 전부 {@code null} 인 항목이 된다. {@code next_stop} 은 "외부
     * 내비게이션 앱 콜백용"이라 좌표 없는 항목을 내보내면 그 용도 자체가 깨지므로, 이미 지나친
     * ({@code SKIPPED}) 항목과 같은 방식으로 건너뛰어 진짜 다음 정차지(좌표 있는 항목)를 고른다.
     * {@code stops[]} 배열 자체(전체 목록)는 그대로 좌표 없는 항목을 보존한다 — 그쪽은 상위 항목에만
     * {@code ●} 가 있고 내부 필드는 별도 등급(API_SPEC §1.13)이라 이 필터를 적용할 근거가 없다.
     */
    boolean hasResolvedTarget(RunStop runStop) {
        if (runStop.isDestination()) {
            return academy != null && academy.hasCoordinates();
        }
        return stopsById.get(runStop.getStopId()) != null || waypointsById.get(runStop.getWaypointId()) != null;
    }

    /** 학원 항목은 이름·주소·좌표를 학원에서 싣는다(Ruling 327) — 탑승 인원은 늘 0 이다. */
    RouteStop toRouteStop(RunStop runStop) {
        String change = runStop.getChange() == null ? null : runStop.getChange().name().toLowerCase(Locale.ROOT);
        Stop stop = stopsById.get(runStop.getStopId());
        if (stop != null) {
            long studentCount = studentCountsByStopId.getOrDefault(stop.getId(), 0L);
            return new RouteStop(runStop.getId(), runStop.getSeq(), stop.getName(), stop.getAddress(), stop.getLat(),
                    stop.getLng(), change, studentCount, false, false, null);
        }
        Waypoint waypoint = waypointsById.get(runStop.getWaypointId());
        if (waypoint != null) {
            return new RouteStop(runStop.getId(), runStop.getSeq(), waypoint.getLabel(), waypoint.getAddress(),
                    waypoint.getLat(), waypoint.getLng(), change, 0L, false, true, waypoint.getId());
        }
        if (runStop.isDestination() && academy != null) {
            return new RouteStop(runStop.getId(), runStop.getSeq(), academy.getName(), academy.getAddress(),
                    academy.getLat(), academy.getLng(), change, 0L, true, false, null);
        }
        return new RouteStop(runStop.getId(), runStop.getSeq(), null, null, null, null, change, 0L,
                runStop.isDestination(), runStop.getWaypointId() != null, runStop.getWaypointId());
    }
}
