package src.backend.monitoring.query;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.entity.RunStop;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.entity.Run;

/**
 * 회차 여러 개의 정차 항목(확정 노선의 현재 판본, {@code seq} 순)을 <b>회차 수와 무관한 쿼리 2건</b>으로 읽는다(BR-247) —
 * 관제 조회 2종({@link AdminAcademyLiveQueryService} · {@link StaffRunLiveQueryService})이 회차마다
 * 확정 노선·정차 순서를 따로 읽던 것을 한 곳으로 모은다. 확정 노선이 없거나 현재 판본이 없는 회차는 빈 목록이다.
 */
@Component
@RequiredArgsConstructor
public class RunOrderedStopsLoader {

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RunStopRepository runStopRepository;

    /** 회차 id → 정차 항목({@code seq} 순). 입력 회차는 전부 키로 담긴다. */
    public Map<Long, List<RunStop>> load(Long academyId, List<Run> runs) {
        List<Long> runIds = runs.stream().map(Run::getId).toList();
        Map<Long, Long> versionIdByRunId = confirmedRouteRepository.findAllByRunIdInAndAcademyId(runIds, academyId)
                .stream()
                .filter(route -> route.getCurrentVersionId() != null)
                .collect(Collectors.toMap(ConfirmedRoute::getRunId, ConfirmedRoute::getCurrentVersionId));
        Map<Long, List<RunStop>> stopsByVersionId = versionIdByRunId.isEmpty() ? Map.of()
                : runStopRepository.findAllByRouteVersionIdInAndAcademyId(
                        versionIdByRunId.values().stream().distinct().toList(), academyId).stream()
                        .collect(Collectors.groupingBy(RunStop::getRouteVersionId));
        return runs.stream().collect(Collectors.toMap(Run::getId, run -> {
            Long versionId = versionIdByRunId.get(run.getId());
            // Map.of() 는 null 키 조회가 NPE 다 — 확정 노선이 없는 회차는 판본이 없다
            return versionId == null ? List.<RunStop>of() : stopsByVersionId.getOrDefault(versionId, List.of());
        }));
    }
}
