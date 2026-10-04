package src.backend.monitoring.query;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import src.backend.routing.query.RunOrderedStopsLoader;
import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.ChangeType;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.security.AuthUser;
import src.backend.location.dto.RunPositionRedisValue;
import src.backend.location.infrastructure.RunPositionStore;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.manager.dto.StaffAssignmentAckView;
import src.backend.monitoring.dto.StaffRunLiveResponse;
import src.backend.routing.entity.RunStop;
import src.backend.routing.repository.WaypointRepository;
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
 * 전체를 다시 훑어야 해 정차 항목을 {@link RunOrderedStopsLoader} 로 따로 읽는다 — 중복 조회는
 * {@code RunLiveStateResolver} 자바독이 이미 인정한 트레이드오프다) ③기사·동승자 이름.
 *
 * <p>클래스에 {@code @Transactional} 을 두지 않는다 — Redis 좌표 읽기({@code RunPositionStore}, 최대 명령 시간 상한
 * 500ms)가 트랜잭션 안에 있으면 그 동안 DB 연결을 쥔다(R46 D #13). 저장소 호출마다 짧은 읽기 트랜잭션이 돈다.
 */
@Service
@RequiredArgsConstructor
public class StaffRunLiveQueryService {

    private final RunRepository runRepository;

    private final BusRepository busRepository;

    private final AssignmentRepository assignmentRepository;

    private final RunLiveStateResolver runLiveStateResolver;

    private final RunPositionStore runPositionStore;

    private final RunOrderedStopsLoader runOrderedStopsLoader;

    private final StopRepository stopRepository;

    private final WaypointRepository waypointRepository;

    private final AcademyRepository academyRepository;

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
        Map<Long, List<StaffAssignmentAckView>> ackViewsByRun = assignmentRepository
                .findAckViewsForStaffDashboard(requester.academyId(), runIds).stream()
                .collect(Collectors.groupingBy(StaffAssignmentAckView::runId));
        // 좌표는 회차 전부를 한 번에 읽는다 — Redis 대기·대체 조회가 회차 수만큼 곱해지지 않게(BR-166·BR-167).
        Map<Long, RunPositionRedisValue> positions = runPositionStore.findAll(runIds);
        // 정차 항목·정차명도 회차 수와 무관한 배치 조회로 읽는다(BR-247)
        Map<Long, List<RunStop>> stopsByRunId = runOrderedStopsLoader.load(requester.academyId(), movingRuns);
        StopNames names = stopNamesOf(requester.academyId(), stopsByRunId);

        List<StaffRunLiveResponse.Run> runResponses = movingRuns.stream()
                .map(run -> toRunResponse(run, busNos, ackViewsByRun, positions.get(run.getId()),
                        stopsByRunId.get(run.getId()), names))
                .toList();
        return new StaffRunLiveResponse(runResponses);
    }

    private Map<Long, String> busNosOf(AuthUser requester, List<Run> runs) {
        List<Long> busIds = runs.stream().map(Run::getBusId).distinct().toList();
        return busRepository.findAllByAcademyIdAndIdIn(requester.academyId(), busIds).stream()
                .collect(Collectors.toMap(Bus::getId, Bus::getBusNo));
    }

    private StaffRunLiveResponse.Run toRunResponse(Run run, Map<Long, String> busNos,
            Map<Long, List<StaffAssignmentAckView>> ackViewsByRun, RunPositionRedisValue latest, List<RunStop> stops,
            StopNames names) {
        RunLiveState state = runLiveStateResolver.resolve(stops, latest);

        // state.lat() 은 유실(stale) 이어도 마지막 값을 그대로 담아 온다(RunLiveState 자바독) —
        // null 로 지울지는 이 소비 측이 정해야 해서, 유실 판정은 stale() 로 본다. lat() != null 로
        // 보면 목표 7(§5.19 2분 유실 시 position=null)이 항상 값을 채워 내보내는 결함이 된다.
        StaffRunLiveResponse.Position position = state.lat() != null && !state.stale()
                ? new StaffRunLiveResponse.Position(state.lat(), state.lng(), state.recordedAt())
                : null;

        List<StaffAssignmentAckView> acks = ackViewsByRun.getOrDefault(run.getId(), List.of());
        return new StaffRunLiveResponse.Run(run.getId(), busNos.get(run.getBusId()),
                lower(run.getDirection().name()), lower(run.getStatus().name()), position,
                nameOf(stops, state.currentStopId(), names), nameOf(stops, state.nextStopId(), names), progressOf(stops),
                RunDelayCalculator.minutesOf(run, stops), nameOf(acks, ManagerRole.DRIVER), nameOf(acks, ManagerRole.ESCORT),
                position == null ? state.receivedAt() : null);
    }

    /** 건너뛴 정차는 버스가 실제로 서지 않으므로 진행도 분모에서 뺀다({@code RunStop#markSkipped} 자바독). */
    private StaffRunLiveResponse.Progress progressOf(List<RunStop> stops) {
        int total = (int) stops.stream().filter(stop -> stop.getChange() != ChangeType.SKIPPED).count();
        int done = (int) stops.stream()
                .filter(stop -> stop.getChange() != ChangeType.SKIPPED && stop.getArrivedAt() != null)
                .count();
        return new StaffRunLiveResponse.Progress(done, total);
    }

    private String nameOf(List<RunStop> stops, Long runStopId, StopNames names) {
        if (runStopId == null) {
            return null;
        }
        return stops.stream().filter(stop -> stop.getId().equals(runStopId)).findFirst()
                .map(stop -> names.of(stop))
                .orElse(null);
    }

    /**
     * 정차 항목이 가리키는 이름을 한 번에 읽어 둔다(BR-247) — 승하차지 마스터 · 강제 경유지 · 학원 항목(Ruling 327) 세 갈래.
     * 학원 이름은 학원 항목이 하나라도 있을 때만 읽는다.
     */
    private StopNames stopNamesOf(Long academyId, Map<Long, List<RunStop>> stopsByRunId) {
        List<RunStop> all = stopsByRunId.values().stream().flatMap(List::stream).toList();
        Map<Long, String> stopNames = stopRepository.findAllByAcademyIdAndIdIn(academyId,
                        all.stream().map(RunStop::getStopId).filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(s -> s.getId(), s -> s.getName()));
        Map<Long, String> waypointLabels = waypointRepository.findAllByIdInAndAcademyId(
                        all.stream().map(RunStop::getWaypointId).filter(Objects::nonNull).distinct().toList(), academyId)
                .stream().collect(Collectors.toMap(w -> w.getId(), w -> w.getLabel()));
        boolean hasAcademyStop = all.stream().anyMatch(stop -> stop.getStopId() == null && stop.getWaypointId() == null);
        String academyName = hasAcademyStop
                ? academyRepository.findById(academyId).map(Academy::getName).orElse(null)
                : null;
        return new StopNames(stopNames, waypointLabels, academyName);
    }

    /** 회차 전체가 함께 쓰는 정차명 조회표. */
    private record StopNames(Map<Long, String> stops, Map<Long, String> waypoints, String academyName) {

        String of(RunStop stop) {
            if (stop.getStopId() != null) {
                return stops.get(stop.getStopId());
            }
            if (stop.getWaypointId() != null) {
                return waypoints.get(stop.getWaypointId());
            }
            return academyName;
        }
    }

    private String nameOf(List<StaffAssignmentAckView> acks, ManagerRole role) {
        return acks.stream().filter(view -> view.role() == role).map(StaffAssignmentAckView::name).findFirst()
                .orElse(null);
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
