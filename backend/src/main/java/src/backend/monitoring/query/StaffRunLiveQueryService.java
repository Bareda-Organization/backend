package src.backend.monitoring.query;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.bus.entity.Bus;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.ChangeType;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.security.AuthUser;
import src.backend.monitoring.dto.StaffAssignmentAckView;
import src.backend.monitoring.dto.StaffRunLiveResponse;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.entity.RunStop;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.routing.repository.WaypointRepository;
import src.backend.monitoring.repository.StaffAssignmentAckRepository;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.run.repository.RunRepository;
import src.backend.student.repository.StopRepository;

/**
 * 전 차량 실시간 위치 조회(§5.18 {@code GET /staff/runs/live}, MON-07, LOC-01).
 *
 * <p>위치·유실·현재/다음 정차 <b>id</b> 판정은 {@link RunLiveStateResolver} 를 그대로 쓴다(Phase 13
 * §2 공용 판정). 이 서비스가 별도로 하는 일은 그 판정이 주지 않는 세 가지뿐이다 — ①정차 id 를
 * 사람이 읽는 이름으로 바꾸는 것 ②{@code progress}·{@code delay_minutes} 계산(둘 다 정차 순서
 * 전체를 다시 훑어야 해 {@link RunStopRepository} 를 독립적으로 다시 부른다 — 중복 조회는
 * {@code RunLiveStateResolver} 자바독이 이미 인정한 트레이드오프다) ③기사·동승자 이름.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StaffRunLiveQueryService {

    private final RunRepository runRepository;

    private final BusRepository busRepository;

    private final StaffAssignmentAckRepository staffAssignmentAckRepository;

    private final RunLiveStateResolver runLiveStateResolver;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RunStopRepository runStopRepository;

    private final StopRepository stopRepository;

    private final WaypointRepository waypointRepository;

    private final Clock clock;

    /** 오늘 운행 중({@code status=moving}) 회차만 담는다 — {@link StaffRunLiveResponse} 자바독. */
    public StaffRunLiveResponse live(AuthUser requester) {
        LocalDate serviceDate = LocalDate.now(clock);
        List<Run> movingRuns = runRepository
                .findAllByAcademyIdAndServiceDateOrderByDepartTimeAsc(requester.academyId(), serviceDate).stream()
                .filter(run -> run.getStatus() == RunStatus.MOVING)
                .toList();
        if (movingRuns.isEmpty()) {
            return new StaffRunLiveResponse(List.of());
        }

        List<Long> runIds = movingRuns.stream().map(Run::getId).toList();
        Map<Long, String> busNos = busNosOf(requester, movingRuns);
        Map<Long, List<StaffAssignmentAckView>> ackViewsByRun = staffAssignmentAckRepository
                .findAckViewsByAcademyIdAndRunIdIn(requester.academyId(), runIds).stream()
                .collect(Collectors.groupingBy(StaffAssignmentAckView::runId));

        List<StaffRunLiveResponse.Run> runResponses = movingRuns.stream()
                .map(run -> toRunResponse(run, busNos, ackViewsByRun))
                .toList();
        return new StaffRunLiveResponse(runResponses);
    }

    private Map<Long, String> busNosOf(AuthUser requester, List<Run> runs) {
        List<Long> busIds = runs.stream().map(Run::getBusId).distinct().toList();
        return busRepository.findAllByAcademyIdAndIdIn(requester.academyId(), busIds).stream()
                .collect(Collectors.toMap(Bus::getId, Bus::getBusNo));
    }

    private StaffRunLiveResponse.Run toRunResponse(Run run, Map<Long, String> busNos,
            Map<Long, List<StaffAssignmentAckView>> ackViewsByRun) {
        RunLiveState state = runLiveStateResolver.resolve(run);
        List<RunStop> stops = orderedStopsOf(run);

        // state.lat() 은 유실(stale) 이어도 마지막 값을 그대로 담아 온다(RunLiveState 자바독) —
        // null 로 지울지는 이 소비 측이 정해야 해서, 유실 판정은 stale() 로 본다. lat() != null 로
        // 보면 목표 7(§5.19 2분 유실 시 position=null)이 항상 값을 채워 내보내는 결함이 된다.
        StaffRunLiveResponse.Position position = state.lat() != null && !state.stale()
                ? new StaffRunLiveResponse.Position(state.lat(), state.lng(), state.recordedAt())
                : null;

        List<StaffAssignmentAckView> acks = ackViewsByRun.getOrDefault(run.getId(), List.of());
        return new StaffRunLiveResponse.Run(run.getId(), busNos.get(run.getBusId()),
                lower(run.getDirection().name()), lower(run.getStatus().name()), position,
                nameOf(stops, state.currentStopId()), nameOf(stops, state.nextStopId()), progressOf(stops),
                delayMinutesOf(run, stops), nameOf(acks, ManagerRole.DRIVER), nameOf(acks, ManagerRole.ESCORT),
                position == null ? state.receivedAt() : null);
    }

    /** 확정 노선이 없으면 빈 목록 — {@link RunLiveStateResolver#resolve} 와 같은 전제. */
    private List<RunStop> orderedStopsOf(Run run) {
        Long currentVersionId = confirmedRouteRepository.findById(run.getId())
                .map(ConfirmedRoute::getCurrentVersionId)
                .orElse(null);
        if (currentVersionId == null) {
            return List.of();
        }
        return runStopRepository.findAllByRouteVersionIdAndAcademyIdOrderBySeq(currentVersionId, run.getAcademyId());
    }

    /** 건너뛴 정차는 버스가 실제로 서지 않으므로 진행도 분모에서 뺀다({@code RunStop#markSkipped} 자바독). */
    private StaffRunLiveResponse.Progress progressOf(List<RunStop> stops) {
        int total = (int) stops.stream().filter(stop -> stop.getChange() != ChangeType.SKIPPED).count();
        int done = (int) stops.stream()
                .filter(stop -> stop.getChange() != ChangeType.SKIPPED && stop.getArrivedAt() != null)
                .count();
        return new StaffRunLiveResponse.Progress(done, total);
    }

    /**
     * Ruling 232 §3.1 — 가장 최근 도착한 정차(정차 순서 {@code seq} 최댓값 중 도착 처리된 것,
     * {@link RunLiveStateResolver} 의 {@code currentStopId} 판정과 같은 기준)의 지연. 도착한 정차가
     * 없으면 실제 출발 지연으로 대신한다. 두 갈래 다 음수를 0 으로 내린다 — 정시·조기 도착을 "마이너스
     * 지연" 으로 보여주는 것은 관계자에게 혼동만 준다는 판단(확신 없는 지점, 보고서 §2).
     */
    private int delayMinutesOf(Run run, List<RunStop> stops) {
        RunStop lastArrived = stops.stream()
                .filter(stop -> stop.getArrivedAt() != null)
                .max(Comparator.comparingInt(RunStop::getSeq))
                .orElse(null);
        if (lastArrived != null && lastArrived.getEta() != null) {
            long minutes = java.time.Duration.between(lastArrived.getEta(), lastArrived.getArrivedAt()).toMinutes();
            return (int) Math.max(0, minutes);
        }
        if (run.getStartedAt() != null) {
            long minutes = java.time.Duration.between(run.getDepartTime(), run.getStartedAt()).toMinutes();
            return (int) Math.max(0, minutes);
        }
        return 0;
    }

    private String nameOf(List<RunStop> stops, Long runStopId) {
        if (runStopId == null) {
            return null;
        }
        return stops.stream().filter(stop -> stop.getId().equals(runStopId)).findFirst()
                .map(this::resolveStopName)
                .orElse(null);
    }

    /** {@code PositionBroadcastListener.currentStopNameOf} 와 같은 두 갈래(승하차지 마스터 · 강제 경유지). */
    private String resolveStopName(RunStop stop) {
        if (stop.getStopId() != null) {
            return stopRepository.findById(stop.getStopId()).map(s -> s.getName()).orElse(null);
        }
        return waypointRepository.findById(stop.getWaypointId()).map(w -> w.getLabel()).orElse(null);
    }

    private String nameOf(List<StaffAssignmentAckView> acks, ManagerRole role) {
        return acks.stream().filter(view -> view.role() == role).map(StaffAssignmentAckView::name).findFirst()
                .orElse(null);
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
