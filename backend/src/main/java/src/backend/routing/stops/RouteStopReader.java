package src.backend.routing.stops;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Weekday;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.entity.Route;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.routing.repository.RunStopRepository;

/**
 * 고정 노선·확정 노선의 정차지 순서 읽기 전용 조회 — {@code student} 모듈의 회차·노선 판정
 * ({@code StudentRunResolver} · {@code StudentRouteQueryService})이 {@code routing} 저장소를 직접
 * 참조하지 않도록 두는 진입점이다(BR-094 · BR-162, ARCHITECTURE §3.3 읽기 방향 — 허용은
 * {@code routing → student} 뿐이라 반대 방향은 이 진입점을 거친다). {@code query} 가 아니라 이 이름의
 * 패키지에 두는 이유는 {@code StudentRunResolver} 가 명령 경로({@code TargetRunLookup})에서도
 * 호출돼, {@code query} 에 두면 §7 "Command는 Query를 호출하지 않는다" 를 새로 어기기 때문이다
 * (CODE_CONVENTIONS §3 "공유 읽기 계층").
 */
@Component
@RequiredArgsConstructor
public class RouteStopReader {

    private final RouteRepository routeRepository;

    private final RouteStopRepository routeStopRepository;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RunStopRepository runStopRepository;

    /** 그 요일·방향의 고정 노선 정차지를 순서대로 — 편성이 없으면 빈 목록(§3.10 "미확정이어도 에러 아님"). */
    public List<Entry> fixedRouteStops(Long academyId, Long busId, Weekday weekday, Direction direction) {
        return routeRepository.findByAcademyIdAndBusIdAndWeekdayAndDirection(academyId, busId, weekday, direction)
                .map(Route::getId)
                .map(routeId -> routeStopRepository.findAllOrderedByRouteIdAndAcademyId(routeId, academyId))
                .orElse(List.of())
                .stream()
                .map(routeStop -> new Entry(routeStop.getStopId(), routeStop.getSeq(), null))
                .toList();
    }

    /** 확정 노선(현재 버전)의 정차지를 순서대로 — 아직 확정되지 않았으면 빈 목록. */
    public List<Entry> confirmedRouteStops(Long runId, Long academyId) {
        Long currentVersionId = confirmedRouteRepository.findById(runId).map(ConfirmedRoute::getCurrentVersionId)
                .orElse(null);
        if (currentVersionId == null) {
            return List.of();
        }
        return runStopRepository.findAllByRouteVersionIdAndAcademyIdOrderBySeq(currentVersionId, academyId).stream()
                .filter(runStop -> runStop.getStopId() != null)
                .map(runStop -> new Entry(runStop.getStopId(), runStop.getSeq(),
                        runStop.getChange() == null ? null : runStop.getChange().name().toLowerCase(Locale.ROOT)))
                .toList();
    }

    /** 그 요일·방향에 그 정차지를 고정 노선에 둔 차량 id 전부 — 확정 전(idle) 회차의 소속 판정(BR-058)에 쓰인다. */
    public Set<Long> busIdsServingStop(Long academyId, Weekday weekday, Direction direction, Long stopId) {
        return Set.copyOf(routeRepository.findBusIdsServingStop(academyId, weekday, direction, stopId));
    }

    /** 정차지 · 순번 · 확정 후 변경 구분(있으면) — 고정 노선 항목은 {@code change} 가 항상 {@code null}. */
    public record Entry(Long stopId, int seq, String change) {
    }
}
