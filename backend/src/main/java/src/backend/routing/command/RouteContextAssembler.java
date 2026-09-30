package src.backend.routing.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.boarding.access.RunRiderRosterAccess;
import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RunRider;
import src.backend.global.common.enums.Weekday;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.request.preview.ApprovalPreviewResolver;
import src.backend.request.preview.ApprovalPreviewResolver.OriginDestination;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.entity.Route;
import src.backend.routing.entity.RouteStop;
import src.backend.routing.entity.RouteVersion;
import src.backend.routing.entity.RunStop;
import src.backend.routing.entity.Waypoint;
import src.backend.routing.pipeline.DailyRoster;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.routing.repository.WaypointRepository;
import src.backend.run.entity.Run;

/**
 * {@link WaypointCommandService} 의 add·remove 가 공유하는 재최적화 문맥 조립(RTE-10, API_SPEC §5.15).
 *
 * <p>{@code WaypointCommandService} 에서 가른 이유는 CODE_CONVENTIONS §20.2 크기 신호다(342줄, BR-101, W02-17) — 구간
 * 판정·배포 분기와 이 조립(학원·고정 노선·확정 노선·명단 8개 저장소를 오가는 조회)은 바뀌는 계기가
 * 다르다. 계산 자체(재최적화 파이프라인 호출)는 여전히 {@code WaypointCommandService} 가 한다 — 이
 * 클래스는 그 계산의 입력을 모으기만 한다.
 */
@Component
@RequiredArgsConstructor
public class RouteContextAssembler {

    private final AcademyRepository academyRepository;
    private final RouteRepository routeRepository;
    private final RouteStopRepository routeStopRepository;
    private final RunRiderRosterAccess runRiderRosterAccess;
    private final WaypointRepository waypointRepository;
    private final ConfirmedRouteRepository confirmedRouteRepository;
    private final RouteVersionRepository routeVersionRepository;
    private final RunStopRepository runStopRepository;
    private final ApprovalPreviewResolver previewResolver;

    /** 재최적화에 필요한 노선·기준점·현재 배포본·명단을 한 번에 모은다 — add·remove 가 공유한다. */
    public RouteContext contextOf(Run run) {
        Long academyId = run.getAcademyId();
        Weekday weekday = Weekday.of(run.getServiceDate());

        Academy academy = academyRepository.findById(academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));
        if (!academy.hasCoordinates()) {
            throw new BusinessException(ErrorCode.ACADEMY_COORDINATES_MISSING);
        }
        Route route = routeRepository
                .findByAcademyIdAndBusIdAndWeekdayAndDirection(academyId, run.getBusId(), weekday, run.getDirection())
                .orElseThrow(() -> new BusinessException(ErrorCode.ROUTE_NOT_CONFIGURED_FOR_RUN));
        List<RouteStop> routeStops = routeStopRepository.findAllOrderedByRouteIdAndAcademyId(route.getId(),
                academyId);
        OriginDestination originDestination = previewResolver.originDestinationOf(academy, routeStops,
                run.getDirection(), academyId);

        // idle 회차(확정 배치가 아직 안 돈 상태)로 호출하면 여기서 걸린다 — ROUTE_NOT_CONFIGURED_FOR_RUN
        // (위 findByAcademyIdAndBusIdAndWeekdayAndDirection)과 원인이 다르다: 저쪽은 고정 노선 자체가
        // 없는 것이고 이쪽은 고정 노선은 있는데 그 회차의 확정 노선이 아직 산출되지 않은 것이다.
        // StaffRunRouteQueryService.route 와 같은 결론(409 RUN_NOT_CONFIRMED)이라 새 코드를 만들지
        // 않는다 — 두 곳 다 "볼·다룰 확정 노선이 없다"는 같은 사용자 관점의 상태다.
        ConfirmedRoute confirmedRoute = confirmedRouteRepository.findById(run.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_CONFIRMED));
        Long currentVersionId = confirmedRoute.getCurrentVersionId();
        // 방어적 조회 — currentVersionId 가 가리키는 route_version 행은 배포 절차상 항상 존재해야
        // 하지만(StaffRunRouteQueryService.route 와 같은 순환 FK 근거), 없으면 결론은 위와 같으므로
        // 별도 코드를 새로 만들지 않고 같은 409 로 묶는다.
        RouteVersion currentVersion = routeVersionRepository.findById(currentVersionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_CONFIRMED));
        List<RunStop> beforeRunStops = runStopRepository.findAllByRouteVersionIdAndAcademyIdOrderBySeq(
                currentVersionId, academyId);

        List<RunRider> riders = runRiderRosterAccess.ridersOf(run.getId(), academyId);
        DailyRoster roster = rosterOf(run, weekday, riders);

        List<Waypoint> appliedWaypoints = waypointRepository.findAllAppliedByRunIdAndAcademyId(run.getId(),
                academyId);

        return new RouteContext(weekday, originDestination, currentVersion, beforeRunStops, roster,
                appliedWaypoints);
    }

    /** 확정 배치가 쌓은 뒤 지금까지 반영된 명단이 기준선이다({@code ApprovalPreviewResolver.candidateRosterOf} 와 같은 근거) — 결석은 뺀다. */
    private static DailyRoster rosterOf(Run run, Weekday weekday, List<RunRider> riders) {
        List<Long> studentIds = new ArrayList<>();
        Map<Long, Long> stopOverrides = new LinkedHashMap<>();
        for (RunRider rider : riders) {
            if (rider.getStatus() == RiderStatus.ABSENT) {
                continue;
            }
            studentIds.add(rider.getStudentId());
            stopOverrides.put(rider.getStudentId(), rider.getStopId());
        }
        return new DailyRoster(run.getAcademyId(), weekday, run.getDirection(), studentIds, stopOverrides);
    }

}
