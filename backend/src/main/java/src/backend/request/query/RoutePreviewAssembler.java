package src.backend.request.query;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RunRider;
import src.backend.request.dto.AffectedStudentResponse;
import src.backend.request.dto.PreviewStopResponse;
import src.backend.request.dto.StopRefResponse;
import src.backend.routing.engine.spec.OrderedStop;
import src.backend.routing.entity.RunStop;
import src.backend.routing.pipeline.RouteComputation;
import src.backend.student.entity.Stop;
import src.backend.student.entity.Student;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

/**
 * 승인 상세(§5.5 상세)의 노선 전/후 대조 조립 — {@link ApprovalQueryService#detail} 이 계산한
 * {@code RouteComputation} 과 확정 배치 산출물({@code RunStop})을 견줘 화면에 낼 값을 만든다.
 *
 * <p>여기 모인 메서드는 재최적화를 <b>실행하지 않는다</b> — 이미 계산된 전/후 값을 견주기만 한다.
 * 목표 12 의 호출 수 계약은 {@link ApprovalQueryService#detail} 이 이 클래스를 부르기 전에 이미
 * 지켜졌으므로, 여기서 호출 횟수를 다시 신경 쓸 필요는 없다.
 */
@Component
@RequiredArgsConstructor
public class RoutePreviewAssembler {

    private final StopRepository stopRepository;
    private final StudentRepository studentRepository;

    /** 전/후 어느 한쪽에라도 등장하는 승하차지 id 전체 — {@link #stopsByIdOf} 조회 범위를 정한다. */
    public Set<Long> unionOfStopIds(List<RunStop> beforeRunStops, RouteComputation computation) {
        Set<Long> ids = new LinkedHashSet<>();
        beforeRunStops.forEach(rs -> {
            if (rs.getStopId() != null) {
                ids.add(rs.getStopId());
            }
        });
        computation.stops().forEach(os -> {
            if (os.stopId() != null) {
                ids.add(os.stopId());
            }
        });
        return ids;
    }

    /** 전/후 대조 내내 반복 조회하지 않도록 승하차지를 한 번에 읽어 id 로 찾아 쓴다. */
    public Map<Long, Stop> stopsByIdOf(Set<Long> stopIds, Long academyId) {
        if (stopIds.isEmpty()) {
            return Map.of();
        }
        return stopRepository.findAllByAcademyIdAndIdIn(academyId, stopIds).stream()
                .collect(Collectors.toMap(Stop::getId, stop -> stop));
    }

    /**
     * 확정 배치 산출물({@code RunStop})을 화면용 정차 목록으로 바꾼다(§5.5 상세 {@code stops_before}).
     * 경유 지점(§5.15) 이름은 해석하지 않는다 — 이 오버로드를 쓰는 승인 조회 경로는 그 정보가 없다.
     */
    public List<PreviewStopResponse> toPreviewStopsFromRunStops(List<RunStop> runStops, Map<Long, Stop> stopsById) {
        return toPreviewStopsFromRunStops(runStops, stopsById, Map.of());
    }

    /**
     * 위와 같되 경유 지점 항목의 이름도 {@code waypointLabelsById} 로 채운다 — 강제 경유 지점
     * 지정·제거(§5.15)의 대조 화면이 쓰는 경로다.
     */
    public List<PreviewStopResponse> toPreviewStopsFromRunStops(List<RunStop> runStops, Map<Long, Stop> stopsById,
            Map<Long, String> waypointLabelsById) {
        return runStops.stream()
                .map(rs -> new PreviewStopResponse(rs.getSeq(),
                        nameOf(rs.getStopId(), rs.getWaypointId(), stopsById, waypointLabelsById), rs.getEta()))
                .toList();
    }

    /**
     * 방금 계산한 재최적화 결과를 화면용 정차 목록으로 바꾼다(§5.5 상세 {@code stops_after}).
     * 경유 지점 이름은 해석하지 않는다 — {@link #toPreviewStopsFromRunStops(List, Map)} 와 같은 이유.
     */
    public List<PreviewStopResponse> toPreviewStopsFromComputation(RouteComputation computation,
            Map<Long, Stop> stopsById) {
        return toPreviewStopsFromComputation(computation, stopsById, Map.of());
    }

    /** 위와 같되 경유 지점 항목의 이름도 {@code waypointLabelsById} 로 채운다. */
    public List<PreviewStopResponse> toPreviewStopsFromComputation(RouteComputation computation,
            Map<Long, Stop> stopsById, Map<Long, String> waypointLabelsById) {
        List<OrderedStop> ordered = computation.stops();
        List<OffsetDateTime> etas = computation.etas();
        List<PreviewStopResponse> stops = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            OrderedStop stop = ordered.get(i);
            stops.add(new PreviewStopResponse(stop.seq(),
                    nameOf(stop.stopId(), stop.waypointId(), stopsById, waypointLabelsById), etas.get(i)));
        }
        return stops;
    }

    /** {@code stopId} 는 승하차지 명단에서, {@code waypointId} 는 경유 지점 label 맵에서 이름을 찾는다. */
    private static String nameOf(Long stopId, Long waypointId, Map<Long, Stop> stopsById,
            Map<Long, String> waypointLabelsById) {
        if (stopId != null) {
            Stop stop = stopsById.get(stopId);
            return stop != null ? stop.getName() : null;
        }
        if (waypointId != null) {
            return waypointLabelsById.get(waypointId);
        }
        return null;
    }

    /** 승하차지 id → 확정 배치의 순번. {@link #reorderedOf}·{@link #removedOf} 대조의 "전" 쪽이다. */
    public Map<Long, Integer> seqMapOfRunStops(List<RunStop> runStops) {
        Map<Long, Integer> map = new LinkedHashMap<>();
        for (RunStop rs : runStops) {
            if (rs.getStopId() != null) {
                map.put(rs.getStopId(), rs.getSeq());
            }
        }
        return map;
    }

    /** 승하차지 id → 재최적화 결과의 순번. {@link #reorderedOf}·{@link #removedOf} 대조의 "후" 쪽이다. */
    public Map<Long, Integer> seqMapOfComputation(RouteComputation computation) {
        Map<Long, Integer> map = new LinkedHashMap<>();
        for (OrderedStop stop : computation.stops()) {
            if (stop.stopId() != null) {
                map.put(stop.stopId(), stop.seq());
            }
        }
        return map;
    }

    /** 전/후 모두에 있지만 순번이 달라진 승하차지(§5.5 상세 {@code reordered[]}). */
    public List<StopRefResponse> reorderedOf(Map<Long, Integer> beforeSeq, Map<Long, Integer> afterSeq,
            Map<Long, Stop> stopsById) {
        return afterSeq.entrySet().stream()
                .filter(entry -> beforeSeq.containsKey(entry.getKey())
                        && !beforeSeq.get(entry.getKey()).equals(entry.getValue()))
                .map(entry -> stopRefOf(entry.getKey(), stopsById))
                .toList();
    }

    /** 전에는 있었으나 후에는 없는 승하차지(§5.5 상세 {@code removed[]}) — 잔여 인원이 0이 된 경우다. */
    public List<StopRefResponse> removedOf(Map<Long, Integer> beforeSeq, Map<Long, Integer> afterSeq,
            Map<Long, Stop> stopsById) {
        return beforeSeq.keySet().stream()
                .filter(stopId -> !afterSeq.containsKey(stopId))
                .map(stopId -> stopRefOf(stopId, stopsById))
                .toList();
    }

    private static StopRefResponse stopRefOf(Long stopId, Map<Long, Stop> stopsById) {
        Stop stop = stopsById.get(stopId);
        return new StopRefResponse(stopId, stop != null ? stop.getName() : null);
    }

    /**
     * 영향 학생(§5.5 상세 {@code affected_students[]}) — 이 승인의 대상 학생 자신과, 순번이 바뀌거나
     * 노선에서 빠진 승하차지를 타는 다른 학생들이다. 도착 예정 시각은 사실 전 구간이 조금씩 밀리지만,
     * 그 정도로 "영향" 을 넓히면 승인 화면이 전원을 영향 학생으로 표시해 이 필드가 무의미해진다 —
     * 정차 위치·순서가 실제로 바뀐 학생만 추린다.
     */
    public List<AffectedStudentResponse> affectedStudentsOf(Long targetStudentId, String targetStudentName,
            List<RunRider> riders, Map<Long, Integer> beforeSeq, Map<Long, Integer> afterSeq) {
        Set<Long> changedStopIds = changedStopIdsOf(beforeSeq, afterSeq);
        Map<Long, String> byId = new LinkedHashMap<>();
        byId.put(targetStudentId, targetStudentName);
        for (RunRider rider : riders) {
            if (rider.getStatus() == RiderStatus.ABSENT || byId.containsKey(rider.getStudentId())) {
                continue;
            }
            if (changedStopIds.contains(rider.getStopId())) {
                String name = studentRepository.findById(rider.getStudentId()).map(Student::getName).orElse(null);
                byId.put(rider.getStudentId(), name);
            }
        }
        return byId.entrySet().stream()
                .map(entry -> new AffectedStudentResponse(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static Set<Long> changedStopIdsOf(Map<Long, Integer> beforeSeq, Map<Long, Integer> afterSeq) {
        Set<Long> changed = new LinkedHashSet<>();
        afterSeq.forEach((stopId, seq) -> {
            if (!seq.equals(beforeSeq.get(stopId))) {
                changed.add(stopId);
            }
        });
        beforeSeq.keySet().stream().filter(stopId -> !afterSeq.containsKey(stopId)).forEach(changed::add);
        return changed;
    }

    /** 정차 목록의 마지막 항목 도착 예정 시각 — 비어 있으면 도착지가 없다는 뜻이라 {@code null}. */
    public OffsetDateTime lastEtaOf(List<PreviewStopResponse> stops) {
        return stops.isEmpty() ? null : stops.get(stops.size() - 1).eta();
    }
}
