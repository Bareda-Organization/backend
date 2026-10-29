package src.backend.run.query;

import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.global.common.enums.ChangeType;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.manager.access.ManagerRunAccess;
import src.backend.global.location.GeoPoint;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.entity.RouteVersion;
import src.backend.routing.entity.RunStop;
import src.backend.routing.query.CurrentRunStopResolver;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.dto.RunRouteResponse;
import src.backend.run.dto.RunRouteResponse.RouteStop;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;

/**
 * 매니저 앱의 실시간 노선 조회(API_SPEC §4.3, RUN-03·M-08·M-09, Ruling 205).
 *
 * <p>{@code current_stop}·{@code next_stop} 은 저장된 포인터가 아니라 {@link RunStop#getArrivedAt()}
 * 에서 <b>매번 계산</b>한다 — {@code RunArrivalCommandService#arrive}(§4.5) 가 도착마다 그 값을
 * 채우므로, 별도 포인터 컬럼 없이 이 값만으로 현재·다음 정차지를 매번 다시 구한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RunRouteQueryService {

    private final ManagerRunAccess managerRunAccess;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RunStopRepository runStopRepository;

    private final RunRouteStopLookupLoader lookupLoader;

    private final RouteVersionRepository routeVersionRepository;

    /** 매니저가 배치된 회차인지 확인한 뒤 확정 노선을 조립한다(§4.3) — 미확정이면 409. */
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
            return new RunRouteResponse(List.of(), null, null, null, List.of(), false);
        }
        return buildFromVersion(run, currentVersionId);
    }

    /**
     * 정차 목록·현재/다음 정류장·건너뜀 안내 조립 — {@link #route} 와 관계자용 §5.19
     * {@code StaffRunRouteQueryService}가 공유한다. 두 호출자가 여기 오기 전까지의
     * 경로(배치 확인 vs 학원 범위 확인)와 버전 미확정 시 처리(빈 200 vs 409)만 서로 다르고, 버전이
     * 정해진 뒤의 조립 자체는 같은 판정을 두 벌 만들지 않도록 여기 하나로 묶는다(과업 지시서 판단
     * 근거). 패키지 전용이라 같은 {@code run.query} 패키지의 관계자용 서비스에서만 호출된다.
     *
     * <p>학원 조건은 요청자가 아니라 <b>회차의 학원</b>으로 건다(BR-014) — 메인 관리자는 {@code academyId}
     * 가 {@code null} 이라 요청자 기준이면 네 조회가 전부 0행이 된다. 두 호출자 모두 여기 오기 전에 접근
     * 판정(배치 확인 · {@code AcademyScope#assertAccessible})을 끝냈다.
     */
    RunRouteResponse buildFromVersion(Run run, Long currentVersionId) {
        List<RunStop> runStops = runStopRepository.findAllByRouteVersionIdAndAcademyIdOrderBySeq(currentVersionId,
                run.getAcademyId());
        RunRouteStopLookup lookup = lookupLoader.load(run, runStops);
        List<RouteStop> stops = runStops.stream().map(lookup::toRouteStop).toList();

        RunStop currentRunStop = CurrentRunStopResolver.resolve(runStops).orElse(null);
        int afterSeq = currentRunStop == null ? -1 : currentRunStop.getSeq();
        RunStop nextRunStop = nextRunStopAfter(runStops, afterSeq, lookup);
        String skippedNotice = skippedNoticeAfter(runStops, afterSeq);

        RouteStop currentStop = currentRunStop == null ? null : lookup.toRouteStop(currentRunStop);
        RouteStop nextStop = nextRunStop == null ? null : lookup.toRouteStop(nextRunStop);

        // 도로 경로는 버전에 딸린 값이다(Ruling 365) — 버전 행이 없는 이례적 상태면 지도가 핀만 그리도록 빈 목록.
        RouteVersion version = routeVersionRepository.findById(currentVersionId).orElse(null);
        List<GeoPoint> roadPath = version == null || version.getRoadPath() == null ? List.of() : version.getRoadPath();
        boolean fallbackUsed = version != null && version.isFallbackUsed();

        return new RunRouteResponse(stops, currentStop, nextStop, skippedNotice, roadPath, fallbackUsed);
    }

    /**
     * 마지막 도착 뒤의 첫 미도착 정차 — 다음은 "마지막 도착 뒤" 에서만 고른다(BR-015). 경유 지점은 도착
     * 처리 대상이 아니라, 그 뒤 승하차지에 도착한 뒤에도 "미도착" 으로 남는다. 미도착 전체의 최소 순번을
     * 고르면 지난 경유 지점이 next_stop 에 고정된다.
     */
    private RunStop nextRunStopAfter(List<RunStop> runStops, int afterSeq, RunRouteStopLookup lookup) {
        return runStops.stream()
                .filter(stop -> stop.getSeq() > afterSeq && stop.getChange() != ChangeType.SKIPPED)
                .filter(lookup::hasResolvedTarget)
                .min(Comparator.comparingInt(RunStop::getSeq))
                .orElse(null);
    }

    /** 마지막 도착 뒤에 건너뛰는 첫 정차의 안내 문구. */
    private String skippedNoticeAfter(List<RunStop> runStops, int afterSeq) {
        return runStops.stream()
                .filter(stop -> stop.getChange() == ChangeType.SKIPPED && stop.getSeq() > afterSeq)
                .min(Comparator.comparingInt(RunStop::getSeq))
                .map(RunStop::getSkipNotice)
                .orElse(null);
    }
}
